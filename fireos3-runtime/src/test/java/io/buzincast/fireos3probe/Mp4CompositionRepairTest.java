package io.buzincast.fireos3probe;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

public class Mp4CompositionRepairTest {
    private static Mp4CompositionRepair.Node leaf(String type,byte[] body) throws IOException {return Mp4CompositionRepair.Node.leaf(type,body);}
    private static Mp4CompositionRepair.Node container(String type,Mp4CompositionRepair.Node...children) {
        int tag=ByteBuffer.wrap(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).getInt();
        Mp4CompositionRepair.Node node=new Mp4CompositionRepair.Node(tag,8,null);node.children=new ArrayList<>(Arrays.asList(children));return node;
    }
    private static Mp4CompositionRepair.Node header(String type,int duration) throws IOException {
        byte[] bytes=new byte[40];ByteBuffer data=ByteBuffer.wrap(bytes);
        if(type.equals("tkhd"))data.putInt(20,duration);else {data.putInt(12,1000);data.putInt(16,duration);}
        return leaf(type,bytes);
    }
    private static Mp4CompositionRepair.Node track(int duration) throws IOException {
        byte[] size=new byte[12];ByteBuffer.wrap(size).putInt(8,4);
        byte[] offsets=new byte[16];ByteBuffer.wrap(offsets).putInt(4,1).putLong(8,16);
        return container("trak",header("tkhd",duration),container("mdia",header("mdhd",duration),container("minf",container("stbl",
            leaf("stsz",size),Mp4CompositionRepair.table("stts",new long[]{2000,0,2000,4000},false),leaf("co64",offsets)))));
    }
    @Test public void repairsCompositionWithoutMovingOrChangingMediaPayload() throws Exception {
        File file=File.createTempFile("composition-",".mp4");
        try {
            byte[] payload={0,1,2,3,4,5,6,7};
            try(OutputStream out=new FileOutputStream(file)) {
                out.write(leaf("ftyp",new byte[0]).encode());out.write(leaf("mdat",payload).encode());
                out.write(container("moov",header("mvhd",8000),track(8000),track(4000)).encode());
            }
            Mp4CompositionRepair.repair(file,Arrays.asList(new long[]{0,2000000,1000000,3000000},null),0);
            byte[] data=java.nio.file.Files.readAllBytes(file.toPath());assertArrayEquals(payload,Arrays.copyOfRange(data,16,24));
            ByteBuffer buffer=ByteBuffer.wrap(data);int at=-1;
            for(int i=24;i<data.length-4;i++)if(buffer.getInt(i)==0x63747473)at=i;
            assertTrue(at>0);assertEquals(0x01000000,buffer.getInt(at+4));assertEquals(4,buffer.getInt(at+8));
            assertEquals(0,buffer.getInt(at+16));assertEquals(1000,buffer.getInt(at+24));assertEquals(-1000,buffer.getInt(at+32));assertEquals(0,buffer.getInt(at+40));
            assertEquals(0x66726565,buffer.getInt(28)); // Original moov converted to free.
            int newest=-1;for(int i=32;i<data.length-4;i++)if(buffer.getInt(i)==0x6d6f6f76)newest=i;
            assertTrue(newest>24);assertEquals(4000,buffer.getInt(newest+4+8+16));
        }finally {file.delete();}
    }
    @Test public void malformedMetadataIsRejectedBeforeMutation() throws Exception {
        File file=File.createTempFile("composition-invalid-",".mp4");byte[] invalid={0,0,0,4,1,2,3,4};
        try {
            java.nio.file.Files.write(file.toPath(),invalid);
            assertThrows(IOException.class,()->Mp4CompositionRepair.repair(file,Collections.singletonList(new long[]{0,2,1}),0));
            assertArrayEquals(invalid,java.nio.file.Files.readAllBytes(file.toPath()));
        }finally {file.delete();}
    }
    @Test public void detectsReorderingAndRefusesTimingOverflow() throws Exception {
        assertFalse(Mp4CompositionRepair.reordered(null));assertFalse(Mp4CompositionRepair.reordered(new long[]{0,1,2}));
        assertTrue(Mp4CompositionRepair.reordered(new long[]{0,2,1}));
        assertThrows(IOException.class,()->Mp4CompositionRepair.ticks(Long.MAX_VALUE,90000));
        assertEquals(90000,Mp4CompositionRepair.ticks(1000000,90000));
    }
}
