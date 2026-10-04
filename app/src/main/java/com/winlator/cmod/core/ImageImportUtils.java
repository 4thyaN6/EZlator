package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

// Copies a user-picked image (any format BitmapFactory can read: PNG, JPEG,
// WebP, BMP, GIF, ICO, HEIF) into app storage, scaled down to a size limit.
// Shortcut tiles are decoded for every shortcut each time the list loads, so
// storing a 4000px phone photo as-is would cost tens of MB per tile.
// 사용자가 고른 이미지(BitmapFactory가 읽는 형식: PNG, JPEG, WebP, BMP, GIF,
// ICO, HEIF)를 크기 상한까지 줄여서 앱 저장소로 복사함. 숏컷 타일은 목록을
// 불러올 때마다 전부 디코딩되므로, 4000px 폰 사진을 그대로 두면 타일 하나에
// 수십 MB가 듦
public final class ImageImportUtils {
    private static final String TAG = "ImageImportUtils";

    private ImageImportUtils() {}

    // Returns true when the scaled copy was written to outFile.
    // maxSide limits the longer edge in pixels; smaller images keep their size.
    // 축소 사본을 outFile에 썼으면 true를 돌려줌.
    // maxSide는 긴 변의 픽셀 상한이며, 더 작은 이미지는 원래 크기를 유지함
    public static boolean importScaled(Context context, Uri uri, File outFile, int maxSide,
                                       Bitmap.CompressFormat format, int quality) {
        Bitmap decoded = null;
        Bitmap result = null;
        try {
            // Pass 1: read only the dimensions
            // 1단계: 크기 정보만 읽음
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Not a decodable image: " + uri);
                return false;
            }

            // Pass 2: decode with a power-of-two subsample that still leaves
            // the longer edge at or above maxSide, so the final resize below
            // only ever shrinks (never upscales a subsampled image)
            // 2단계: 긴 변이 maxSide 이상으로 남는 범위에서 2의 거듭제곱으로
            // 솎아서 디코딩함. 그래야 아래 최종 조정이 항상 축소만 함
            int longer = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longer / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) return false;

            // Exact downscale to maxSide on the longer edge
            // 긴 변 기준 maxSide로 정확히 축소함
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            float scale = Math.min(1f, (float) maxSide / Math.max(w, h));

            // Camera photos store rotation in EXIF instead of rotating pixels
            // 카메라 사진은 픽셀을 돌리지 않고 회전값을 EXIF에 저장함
            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            int rotation = readExifRotation(context, uri);
            if (rotation != 0) matrix.postRotate(rotation);

            result = (scale < 1f || rotation != 0)
                    ? Bitmap.createBitmap(decoded, 0, 0, w, h, matrix, true)
                    : decoded;

            File parent = outFile.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                if (!result.compress(format, quality, out)) return false;
                out.flush();
            }
            return true;
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "Image import failed: " + uri, e);
            if (outFile.isFile()) outFile.delete();
            return false;
        } finally {
            // Bitmaps here are temporary; the caller re-decodes the saved file
            // 여기의 비트맵은 임시용임. 호출부는 저장된 파일을 다시 디코딩함
            if (result != null && result != decoded) result.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    // Rotation in degrees from EXIF, 0 when absent or unreadable (PNG, ICO...)
    // EXIF의 회전 각도임. 정보가 없거나 못 읽으면 0 (PNG, ICO 등)
    private static int readExifRotation(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
            // Formats without EXIF land here; no rotation needed
            // EXIF가 없는 형식은 여기로 옴. 회전 불필요
        }
        return 0;
    }
}
