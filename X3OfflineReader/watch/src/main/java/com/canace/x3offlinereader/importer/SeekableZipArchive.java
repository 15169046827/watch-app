package com.canace.x3offlinereader.importer;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Small read-only ZIP central-directory reader that works directly on seekable SAF descriptors. */
final class SeekableZipArchive implements AutoCloseable {
    private static final int EOCD=0x06054b50,ZIP64_LOCATOR=0x07064b50,ZIP64_EOCD=0x06064b50,CENTRAL=0x02014b50,LOCAL=0x04034b50;
    private static final long UINT32=0xffff_ffffL;
    private static final int MAX_ENTRIES=8192,MAX_CENTRAL=64*1024*1024;
    private static final Charset CP437=Charset.forName("CP437");
    private final FileInputStream input;
    private final FileChannel channel;
    private final List<Entry> entries;
    private final Map<String,Entry> byName;

    static SeekableZipArchive open(File file)throws Exception{return new SeekableZipArchive(new FileInputStream(file));}
    static SeekableZipArchive open(FileInputStream input)throws Exception{return new SeekableZipArchive(input);}

    private SeekableZipArchive(FileInputStream input)throws Exception{
        this.input=input;channel=input.getChannel();
        List<Entry> parsed;
        try{parsed=parse();}catch(Exception failure){try{input.close();}catch(Exception ignored){}throw failure;}
        entries=Collections.unmodifiableList(parsed);Map<String,Entry> index=new LinkedHashMap<>();for(Entry entry:entries)if(index.put(entry.name,entry)!=null)throw new IllegalArgumentException("压缩包存在重复路径");byName=Collections.unmodifiableMap(index);
    }

    List<Entry> entries(){return entries;}
    Entry getEntry(String name){return byName.get(name);}

    InputStream open(Entry entry)throws Exception{
        ByteBuffer local=read(entry.localOffset,30);if(local.getInt(0)!=LOCAL)throw new IllegalArgumentException("ZIP 本地文件头无效");
        int flags=u16(local,6),method=u16(local,8),nameLength=u16(local,26),extraLength=u16(local,28);
        if((flags&1)!=0||flags!=entry.flags||method!=entry.method)throw new IllegalArgumentException("不支持的 ZIP 条目");
        long dataOffset=checkedAdd(entry.localOffset,30L+nameLength+extraLength);InputStream raw=new SliceInputStream(channel,dataOffset,entry.compressedSize);
        if(method==0)return raw;if(method==8)return new InflaterInputStream(raw,new Inflater(true),64*1024);throw new IllegalArgumentException("不支持的 ZIP 压缩方式");
    }

    private List<Entry> parse()throws Exception{
        long length=channel.size();if(length<22)throw new IllegalArgumentException("ZIP 文件过短");int tailLength=(int)Math.min(length,22L+65535L);long tailOffset=length-tailLength;ByteBuffer tail=read(tailOffset,tailLength);int eocdIndex=-1;
        for(int i=tailLength-22;i>=0;i--)if(tail.getInt(i)==EOCD&&i+22+u16(tail,i+20)==tailLength){eocdIndex=i;break;}
        if(eocdIndex<0)throw new IllegalArgumentException("ZIP 中央目录缺失");long eocdOffset=tailOffset+eocdIndex,entryCount=u16(tail,eocdIndex+10),centralSize=u32(tail,eocdIndex+12),centralOffset=u32(tail,eocdIndex+16);
        if(entryCount==0xffff||centralSize==UINT32||centralOffset==UINT32){
            if(eocdOffset<20)throw new IllegalArgumentException("ZIP64 定位器缺失");ByteBuffer locator=read(eocdOffset-20,20);if(locator.getInt(0)!=ZIP64_LOCATOR)throw new IllegalArgumentException("ZIP64 定位器无效");long recordOffset=locator.getLong(8);ByteBuffer record=read(recordOffset,56);if(record.getInt(0)!=ZIP64_EOCD)throw new IllegalArgumentException("ZIP64 中央目录无效");entryCount=record.getLong(32);centralSize=record.getLong(40);centralOffset=record.getLong(48);
        }
        if(entryCount<0||entryCount>MAX_ENTRIES||centralSize<0||centralSize>MAX_CENTRAL||centralOffset<0||checkedAdd(centralOffset,centralSize)>eocdOffset)throw new IllegalArgumentException("ZIP 中央目录超出限制");
        List<Entry> result=new ArrayList<>((int)entryCount);long cursor=centralOffset;
        for(long i=0;i<entryCount;i++){
            ByteBuffer fixed=read(cursor,46);if(fixed.getInt(0)!=CENTRAL)throw new IllegalArgumentException("ZIP 中央目录条目无效");int flags=u16(fixed,8),method=u16(fixed,10),nameLength=u16(fixed,28),extraLength=u16(fixed,30),commentLength=u16(fixed,32);if((flags&1)!=0||(method!=0&&method!=8))throw new IllegalArgumentException("不支持的 ZIP 条目");
            long compressed=u32(fixed,20),size=u32(fixed,24),localOffset=u32(fixed,42);byte[] nameBytes=bytes(checkedAdd(cursor,46),nameLength);String name=new String(nameBytes,(flags&0x800)!=0?StandardCharsets.UTF_8:CP437);
            if(compressed==UINT32||size==UINT32||localOffset==UINT32){long[] zip64=zip64(bytes(checkedAdd(cursor,46L+nameLength),extraLength),size==UINT32,compressed==UINT32,localOffset==UINT32);int p=0;if(size==UINT32)size=zip64[p++];if(compressed==UINT32)compressed=zip64[p++];if(localOffset==UINT32)localOffset=zip64[p];}
            if(size<0||compressed<0||localOffset<0)throw new IllegalArgumentException("ZIP64 条目无效");result.add(new Entry(name,size,compressed,method,flags,localOffset));cursor=checkedAdd(cursor,46L+nameLength+extraLength+commentLength);
        }
        if(cursor>checkedAdd(centralOffset,centralSize))throw new IllegalArgumentException("ZIP 中央目录大小无效");return result;
    }

    private static long[] zip64(byte[] extra,boolean size,boolean compressed,boolean offset){ByteBuffer data=ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);while(data.remaining()>=4){int id=Short.toUnsignedInt(data.getShort()),length=Short.toUnsignedInt(data.getShort());if(length>data.remaining())break;if(id==1){ByteBuffer value=data.slice().order(ByteOrder.LITTLE_ENDIAN);int needed=(size?1:0)+(compressed?1:0)+(offset?1:0);if(value.remaining()<needed*8)throw new IllegalArgumentException("ZIP64 条目数据不完整");long[] result=new long[needed];for(int i=0;i<needed;i++)result[i]=value.getLong();return result;}data.position(data.position()+length);}throw new IllegalArgumentException("ZIP64 条目数据缺失");}
    private byte[] bytes(long offset,int length)throws Exception{ByteBuffer value=read(offset,length);byte[] bytes=new byte[length];value.get(bytes);return bytes;}
    private ByteBuffer read(long offset,int length)throws Exception{if(offset<0||length<0||checkedAdd(offset,length)>channel.size())throw new EOFException();ByteBuffer value=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);long position=offset;while(value.hasRemaining()){int count=channel.read(value,position);if(count<0)throw new EOFException();if(count==0)throw new IOException("ZIP 随机读取无进展");position+=count;}value.flip();return value;}
    private static int u16(ByteBuffer value,int offset){return Short.toUnsignedInt(value.getShort(offset));}
    private static long u32(ByteBuffer value,int offset){return Integer.toUnsignedLong(value.getInt(offset));}
    private static long checkedAdd(long left,long right){if(left<0||right<0||left>Long.MAX_VALUE-right)throw new IllegalArgumentException("ZIP 偏移无效");return left+right;}
    @Override public void close()throws Exception{input.close();}

    static final class Entry {
        final String name;final long size,compressedSize;final int method,flags;final long localOffset;
        Entry(String name,long size,long compressedSize,int method,int flags,long localOffset){this.name=name;this.size=size;this.compressedSize=compressedSize;this.method=method;this.flags=flags;this.localOffset=localOffset;}
    }
    private static final class SliceInputStream extends InputStream {
        private final FileChannel channel;private long position,remaining;
        SliceInputStream(FileChannel channel,long position,long remaining){this.channel=channel;this.position=position;this.remaining=remaining;}
        @Override public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&0xff;}
        @Override public int read(byte[] buffer,int offset,int length)throws IOException{if(remaining==0)return -1;int wanted=(int)Math.min(length,remaining);ByteBuffer target=ByteBuffer.wrap(buffer,offset,wanted);int count=channel.read(target,position);if(count<0)throw new EOFException();if(count==0)return 0;position+=count;remaining-=count;return count;}
        @Override public long skip(long count){long skipped=Math.min(Math.max(0,count),remaining);position+=skipped;remaining-=skipped;return skipped;}
    }
}
