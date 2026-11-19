#include <jni.h>
#include <openssl/evp.h>
#include <openssl/crypto.h>
#include <stdlib.h>

#define MAX_PLAIN (4*1024*1024+32)
static void fail(JNIEnv *env,const char *type,const char *message) {
    if((*env)->ExceptionCheck(env))return;
    jclass cls=(*env)->FindClass(env,type);
    if(cls) {(*env)->ThrowNew(env,cls,message);(*env)->DeleteLocalRef(env,cls);}
}
JNIEXPORT jbyteArray JNICALL Java_io_buzincast_fireos3probe_LegacyRelayCrypto_crypt(
    JNIEnv *env,jclass cls,jboolean encrypt,jbyteArray key,jbyteArray nonce,jbyteArray aad,jbyteArray data) {
    (void)cls;
    if(!key || !nonce || !aad || !data || (*env)->GetArrayLength(env,key)!=32 ||
       (*env)->GetArrayLength(env,nonce)!=12 || (*env)->GetArrayLength(env,aad)!=8) {
        fail(env,"java/lang/IllegalArgumentException","Invalid relay key, nonce or AAD length");return NULL;
    }
    int size=(*env)->GetArrayLength(env,data);
    if(size>(encrypt?MAX_PLAIN:MAX_PLAIN+16) || (!encrypt && size<16)) {
        fail(env,"java/lang/IllegalArgumentException","Invalid relay payload length");return NULL;
    }
    unsigned char k[32]={0},n[12]={0},a[8]={0};
    unsigned char *input=OPENSSL_malloc(size?size:1),*output=OPENSSL_malloc(size+16);
    EVP_CIPHER_CTX *ctx=EVP_CIPHER_CTX_new();jbyteArray result=NULL;int length=0,total=0,ok=0;
    if(!input || !output || !ctx) {fail(env,"java/lang/OutOfMemoryError","Relay cipher allocation failed");goto cleanup;}
    (*env)->GetByteArrayRegion(env,key,0,32,(jbyte*)k);(*env)->GetByteArrayRegion(env,nonce,0,12,(jbyte*)n);
    (*env)->GetByteArrayRegion(env,aad,0,8,(jbyte*)a);
    if(size)(*env)->GetByteArrayRegion(env,data,0,size,(jbyte*)input);
    if((*env)->ExceptionCheck(env))goto cleanup;
    if(encrypt) {
        if(EVP_EncryptInit_ex(ctx,EVP_aes_256_gcm(),NULL,k,n)!=1 || EVP_EncryptUpdate(ctx,NULL,&length,a,8)!=1 ||
           EVP_EncryptUpdate(ctx,output,&length,input,size)!=1)goto cleanup;
        total=length;if(EVP_EncryptFinal_ex(ctx,output+total,&length)!=1)goto cleanup;total+=length;
        if(EVP_CIPHER_CTX_ctrl(ctx,EVP_CTRL_GCM_GET_TAG,16,output+total)!=1)goto cleanup;total+=16;
    } else {
        if(EVP_DecryptInit_ex(ctx,EVP_aes_256_gcm(),NULL,k,n)!=1 || EVP_DecryptUpdate(ctx,NULL,&length,a,8)!=1 ||
           EVP_DecryptUpdate(ctx,output,&length,input,size-16)!=1 || EVP_CIPHER_CTX_ctrl(ctx,EVP_CTRL_GCM_SET_TAG,16,input+size-16)!=1)goto cleanup;
        total=length;
        if(EVP_DecryptFinal_ex(ctx,output+total,&length)!=1) {fail(env,"java/lang/SecurityException","Relay authentication failed");goto cleanup;}
        total+=length;
    }
    result=(*env)->NewByteArray(env,total);
    if(result)(*env)->SetByteArrayRegion(env,result,0,total,(jbyte*)output);
    ok=1;
cleanup:
    OPENSSL_cleanse(k,sizeof(k));OPENSSL_cleanse(n,sizeof(n));OPENSSL_cleanse(a,sizeof(a));
    if(input) {OPENSSL_cleanse(input,size?size:1);OPENSSL_free(input);}
    if(output) {OPENSSL_cleanse(output,size+16);OPENSSL_free(output);}
    EVP_CIPHER_CTX_free(ctx);
    if(!ok && !(*env)->ExceptionCheck(env))fail(env,"java/io/IOException","Relay cipher failed");
    return result;
}
