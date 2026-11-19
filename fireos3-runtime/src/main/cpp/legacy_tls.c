#include <jni.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>

/* Registry IDs, never exposed pointer addresses. Closing removes the ID and
 * interrupts I/O; the last in-flight operation releases SSL/context/fd. */
typedef struct tls_socket {
    int64_t id;int fd,refs;bool server;atomic_bool closing;
    SSL_CTX *context;SSL *ssl;pthread_mutex_t io;struct tls_socket *next;
} tls_socket;
static pthread_mutex_t registry_lock=PTHREAD_MUTEX_INITIALIZER;
static tls_socket *registry;static int64_t sequence;
static atomic_int live_sockets;
static void destroy(tls_socket *s) {if(!s)return;SSL_free(s->ssl);SSL_CTX_free(s->context);if(s->fd>=0)close(s->fd);pthread_mutex_destroy(&s->io);free(s);atomic_fetch_sub(&live_sockets,1);}
static void fail(JNIEnv *env,const char *type,const char *message) {
    if((*env)->ExceptionCheck(env))return;jclass cls=(*env)->FindClass(env,type);
    if(cls) {(*env)->ThrowNew(env,cls,message);(*env)->DeleteLocalRef(env,cls);}
}
static tls_socket *allocate(void) {
    tls_socket *s=calloc(1,sizeof(*s));if(!s)return NULL;s->fd=-1;atomic_init(&s->closing,false);
    if(pthread_mutex_init(&s->io,NULL)) {free(s);return NULL;}atomic_fetch_add(&live_sockets,1);return s;
}
static int64_t publish(tls_socket *s) {
    pthread_mutex_lock(&registry_lock);
    if(sequence==INT64_MAX) {pthread_mutex_unlock(&registry_lock);return 0;}
    s->id=++sequence;s->refs=1;s->next=registry;registry=s;
    pthread_mutex_unlock(&registry_lock);return s->id;
}
static tls_socket *acquire(JNIEnv *env,jlong id) {
    pthread_mutex_lock(&registry_lock);tls_socket *s=registry;
    while(s && s->id!=id)s=s->next;if(s)s->refs++;pthread_mutex_unlock(&registry_lock);
    if(!s)fail(env,"java/net/SocketException","TLS socket is closed");return s;
}
static void release(tls_socket *s) {
    pthread_mutex_lock(&registry_lock);bool last=--s->refs==0;pthread_mutex_unlock(&registry_lock);if(last)destroy(s);
}
static void io_error(JNIEnv *env,tls_socket *s,int result,int saved_errno) {
    int error=SSL_get_error(s->ssl,result);
    if(atomic_load(&s->closing))fail(env,"java/net/SocketException","TLS socket is closed");
    else if((error==SSL_ERROR_WANT_READ || error==SSL_ERROR_WANT_WRITE || error==SSL_ERROR_SYSCALL) &&
            (saved_errno==EAGAIN || saved_errno==EWOULDBLOCK || saved_errno==ETIMEDOUT))
        fail(env,"java/net/SocketTimeoutException","TLS I/O timed out");
    else fail(env,"javax/net/ssl/SSLException","TLS peer or record validation failed");
}
#define JNI(name) Java_io_buzincast_fireos3probe_LegacyNativeTls_##name
JNIEXPORT jlong JNICALL JNI(nCreate)(JNIEnv *env,jclass cls,jbyteArray private_bytes,jbyteArray certificate_bytes) {
    tls_socket *s=allocate();EVP_PKEY *key=NULL;X509 *cert=NULL;PKCS8_PRIV_KEY_INFO *info=NULL;
    unsigned char *encoded=NULL;int key_length=0;int64_t id=0;
    if(!s || !private_bytes || !certificate_bytes)goto done;
    key_length=(*env)->GetArrayLength(env,private_bytes);int certificate_length=(*env)->GetArrayLength(env,certificate_bytes);
    if(key_length<1 || key_length>16384 || certificate_length<1 || certificate_length>16384)goto done;
    encoded=OPENSSL_malloc((size_t)(key_length>certificate_length?key_length:certificate_length));if(!encoded)goto done;
    (*env)->GetByteArrayRegion(env,private_bytes,0,key_length,(jbyte *)encoded);if((*env)->ExceptionCheck(env))goto done;
    const unsigned char *cursor=encoded;info=d2i_PKCS8_PRIV_KEY_INFO(NULL,&cursor,key_length);if(!info || !(key=EVP_PKCS82PKEY(info)))goto done;
    OPENSSL_cleanse(encoded,(size_t)key_length);
    (*env)->GetByteArrayRegion(env,certificate_bytes,0,certificate_length,(jbyte *)encoded);if((*env)->ExceptionCheck(env))goto done;
    cursor=encoded;cert=d2i_X509(NULL,&cursor,certificate_length);if(!cert)goto done;
    s->context=SSL_CTX_new(TLS_server_method());if(!s->context)goto done;
    if(!SSL_CTX_set_min_proto_version(s->context,TLS1_2_VERSION) || !SSL_CTX_set_max_proto_version(s->context,TLS1_2_VERSION) ||
            !SSL_CTX_set_cipher_list(s->context,"ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-SHA256:ECDHE-RSA-AES128-SHA") ||
            !SSL_CTX_use_certificate(s->context,cert) || !SSL_CTX_use_PrivateKey(s->context,key) || !SSL_CTX_check_private_key(s->context))goto done;
    SSL_CTX_set_options(s->context,SSL_OP_NO_COMPRESSION|SSL_OP_NO_RENEGOTIATION);
    SSL_CTX_sess_set_cache_size(s->context,16);SSL_CTX_set_timeout(s->context,300);
    s->fd=socket(AF_INET,SOCK_STREAM,0);if(s->fd<0)goto done;
    int yes=1;if(setsockopt(s->fd,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof(yes)))goto done;
    s->server=true;id=publish(s);if(id)s=NULL;
done:
    OPENSSL_clear_free(encoded,key_length>0?(size_t)key_length:0);PKCS8_PRIV_KEY_INFO_free(info);EVP_PKEY_free(key);X509_free(cert);destroy(s);
    if(!id)fail(env,"java/io/IOException","Could not create owned TLS server");return id;
}
JNIEXPORT jint JNICALL JNI(nBind)(JNIEnv *env,jclass cls,jlong id,jbyteArray address,jint port,jint backlog) {
    tls_socket *s=acquire(env,id);if(!s)return 0;int actual=0;
    if(!s->server || !address || (*env)->GetArrayLength(env,address)!=4 || port<0 || port>65535 || backlog<1 || backlog>128)goto done;
    struct sockaddr_in endpoint={0};endpoint.sin_family=AF_INET;endpoint.sin_port=htons((uint16_t)port);
    (*env)->GetByteArrayRegion(env,address,0,4,(jbyte *)&endpoint.sin_addr);if((*env)->ExceptionCheck(env))goto done;
    if(bind(s->fd,(struct sockaddr *)&endpoint,sizeof(endpoint)) || listen(s->fd,backlog))goto done;
    socklen_t size=sizeof(endpoint);if(getsockname(s->fd,(struct sockaddr *)&endpoint,&size))goto done;
    actual=ntohs(endpoint.sin_port);
done:release(s);if(!actual)fail(env,"java/net/SocketException","Could not bind Cast TLS listener");return actual;
}
JNIEXPORT jlongArray JNICALL JNI(nAccept)(JNIEnv *env,jclass cls,jlong id) {
    tls_socket *parent=acquire(env,id);if(!parent)return NULL;
    tls_socket *s=NULL;jlongArray result=NULL;struct sockaddr_in peer={0},local={0};socklen_t length=sizeof(peer);
    if(!parent->server)goto done;
    while(!atomic_load(&parent->closing)) {
        struct pollfd ready={parent->fd,POLLIN,0};int polled=poll(&ready,1,1000);
        if(polled>0)break;if(polled<0 && errno!=EINTR)goto done;
    }
    if(atomic_load(&parent->closing))goto done;
    int fd=accept(parent->fd,(struct sockaddr *)&peer,&length);if(fd<0)goto done;
    s=allocate();if(!s) {close(fd);goto done;}s->fd=fd;
    if(atomic_load(&parent->closing) || !(s->ssl=SSL_new(parent->context)) || SSL_set_fd(s->ssl,fd)!=1)goto done;
    SSL_set_accept_state(s->ssl);length=sizeof(local);if(getsockname(fd,(struct sockaddr *)&local,&length))goto done;
    int64_t child=publish(s);if(!child)goto done;s=NULL;
    jlong values[]={child,(jlong)ntohl(peer.sin_addr.s_addr),ntohs(peer.sin_port),(jlong)ntohl(local.sin_addr.s_addr),ntohs(local.sin_port)};
    result=(*env)->NewLongArray(env,5);if(result)(*env)->SetLongArrayRegion(env,result,0,5,values);
    if(!result || (*env)->ExceptionCheck(env)) {
        // Java could not take ownership. Release the published child here.
        pthread_mutex_lock(&registry_lock);tls_socket **entry=&registry;while(*entry && (*entry)->id!=child)entry=&(*entry)->next;
        tls_socket *lost=*entry;if(lost)*entry=lost->next;pthread_mutex_unlock(&registry_lock);destroy(lost);
    }
done:destroy(s);release(parent);if(!result)fail(env,"java/net/SocketException","TLS accept ended");return result;
}
JNIEXPORT void JNICALL JNI(nClose)(JNIEnv *env,jclass cls,jlong id) {
    pthread_mutex_lock(&registry_lock);tls_socket **entry=&registry;while(*entry && (*entry)->id!=id)entry=&(*entry)->next;
    tls_socket *s=*entry;bool last=false;
    if(s) {*entry=s->next;atomic_store(&s->closing,true);shutdown(s->fd,SHUT_RDWR);last=--s->refs==0;}
    pthread_mutex_unlock(&registry_lock);if(last)destroy(s);
}
JNIEXPORT jstring JNICALL JNI(nHandshake)(JNIEnv *env,jclass cls,jlong id) {
    tls_socket *s=acquire(env,id);if(!s)return NULL;jstring result=NULL;pthread_mutex_lock(&s->io);
    if(s->ssl) {int status=SSL_accept(s->ssl),saved=errno;
        if(status==1) {const char *cipher=SSL_CIPHER_standard_name(SSL_get_current_cipher(s->ssl));result=(*env)->NewStringUTF(env,cipher?cipher:SSL_get_cipher_name(s->ssl));}
        else io_error(env,s,status,saved);
    } else fail(env,"java/net/SocketException","Not a TLS client socket");
    pthread_mutex_unlock(&s->io);release(s);return result;
}
JNIEXPORT jint JNICALL JNI(nRead)(JNIEnv *env,jclass cls,jlong id,jbyteArray data,jint offset,jint count) {
    if(!data || offset<0 || count<0 || offset>(*env)->GetArrayLength(env,data) || count>(*env)->GetArrayLength(env,data)-offset) {fail(env,"java/lang/IndexOutOfBoundsException","Invalid TLS buffer");return -1;}
    if(!count)return 0;if(count>65536)count=65536;
    tls_socket *s=acquire(env,id);if(!s)return -1;int result=-1;unsigned char *bytes=OPENSSL_malloc((size_t)count);
    if(!bytes) {fail(env,"java/io/IOException","TLS buffer unavailable");release(s);return -1;}
    pthread_mutex_lock(&s->io);
    if(s->ssl) {int n=SSL_read(s->ssl,bytes,count),saved=errno;
        if(n>0) {(*env)->SetByteArrayRegion(env,data,offset,n,(jbyte *)bytes);result=n;}
        else if(SSL_get_error(s->ssl,n)!=SSL_ERROR_ZERO_RETURN)io_error(env,s,n,saved);
    } else fail(env,"java/net/SocketException","Not a TLS client socket");
    pthread_mutex_unlock(&s->io);OPENSSL_clear_free(bytes,(size_t)count);release(s);return result;
}
JNIEXPORT void JNICALL JNI(nWrite)(JNIEnv *env,jclass cls,jlong id,jbyteArray data,jint offset,jint count) {
    if(!data || offset<0 || count<0 || count>65536 || offset>(*env)->GetArrayLength(env,data) || count>(*env)->GetArrayLength(env,data)-offset) {fail(env,"java/lang/IndexOutOfBoundsException","Invalid TLS buffer");return;}
    if(!count)return;tls_socket *s=acquire(env,id);if(!s)return;
    unsigned char *bytes=OPENSSL_malloc((size_t)count);if(!bytes) {fail(env,"java/io/IOException","TLS buffer unavailable");release(s);return;}
    (*env)->GetByteArrayRegion(env,data,offset,count,(jbyte *)bytes);pthread_mutex_lock(&s->io);
    if(!(*env)->ExceptionCheck(env) && s->ssl) {
        int written=0;while(written<count) {int n=SSL_write(s->ssl,bytes+written,count-written),saved=errno;
            if(n<=0) {io_error(env,s,n,saved);break;}written+=n;}
    } else if(!(*env)->ExceptionCheck(env))fail(env,"java/net/SocketException","Not a TLS client socket");
    pthread_mutex_unlock(&s->io);OPENSSL_clear_free(bytes,(size_t)count);release(s);
}
JNIEXPORT void JNICALL JNI(nOption)(JNIEnv *env,jclass cls,jlong id,jint kind,jint value) {
    tls_socket *s=acquire(env,id);if(!s)return;int result=-1;
    if(kind==1 && value>=0) {struct timeval timeout={value/1000,(value%1000)*1000};
        result=setsockopt(s->fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
        if(!result)result=setsockopt(s->fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    } else if(kind==2) {int enabled=value?1:0;result=setsockopt(s->fd,IPPROTO_TCP,TCP_NODELAY,&enabled,sizeof(enabled));}
    release(s);if(result)fail(env,"java/net/SocketException","Could not configure TLS socket");
}
JNIEXPORT jint JNICALL JNI(nLive)(JNIEnv *env,jclass cls) {return atomic_load(&live_sockets);}
