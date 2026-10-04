package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

public class Downloader {

    /**
     * Byte-level progress for a single file download.
     * total is -1 when the server sends no Content-Length.
     * 파일 하나를 받는 동안의 바이트 단위 진행률임.
     * 서버가 Content-Length를 안 보내면 total이 -1임
     */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    // Original signature kept so every existing caller compiles unchanged.
    // 기존 호출부가 전부 그대로 컴파일되도록 원래 시그니처를 유지함
    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, ProgressListener listener) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            // Read from the SAME connection that was just opened. The old code
            // called url.openStream() here, which silently opened a second
            // request - so the connection above was wasted and its
            // Content-Length could never be matched to the bytes read.
            // 방금 연 그 연결에서 그대로 읽음. 예전 코드는 여기서
            // url.openStream()을 불러 요청을 몰래 한 번 더 보냈음 - 그래서 위의
            // 연결은 버려졌고, 그 Content-Length를 실제로 읽은 바이트와 맞춰 볼
            // 수도 없었음
            long total = connection.getContentLengthLong();
            InputStream input = connection.getInputStream();

            // Output stream
            OutputStream output = new FileOutputStream(file.getAbsolutePath());

            byte[] data = new byte[1024];

            long downloaded = 0;
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                downloaded += count;
                if (listener != null) listener.onProgress(downloaded, total);
            }

            // flushing output
            output.flush();

            // closing streams
            output.close();
            input.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            InputStream input = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}