package com.blog4j.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.data.redis.serializer.SerializationException;

public class CompressingUtils {
    public static byte[] compressGzip(byte[] data) {
        byte[] ret = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            byteArrayOutputStream = new ByteArrayOutputStream();
            GZIPOutputStream gzipOutputStream= new GZIPOutputStream(byteArrayOutputStream);
            gzipOutputStream.write(data);
            gzipOutputStream.close();   //    finish

            ret = byteArrayOutputStream.toByteArray();
            byteArrayOutputStream.flush();
            byteArrayOutputStream.close();
        } catch (IOException e) {
            throw new SerializationException("Unable to compress data", e);
        }
        return ret;
    }

    public static byte[] decompressGzip(byte[] data) {
        if ((data == null) || (data.length < 2)) {
            return data;
        } else {
            if (isCompressedGzip(data)) {
                byte[] ret = null;
                ByteArrayOutputStream out = null;
                try {
                    out = new ByteArrayOutputStream();
                    GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(data));
                    IOUtils.copy(gzipInputStream, out);
                    gzipInputStream.close();
                    ret = out.toByteArray();
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    throw new SerializationException("Unable to decompress data", e);
                }
                return ret;
            }
            return data;
        }
    }
    public static boolean isCompressedGzip(byte[] source) {
        //log.info("Gzip isCompressed :: {}", (source[0] == (byte) (GZIPInputStream.GZIP_MAGIC)) && (source[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8)));
        return (source[0] == (byte) (GZIPInputStream.GZIP_MAGIC)) && (source[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8));
    }

}
