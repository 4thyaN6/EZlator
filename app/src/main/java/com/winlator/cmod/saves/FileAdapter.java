package com.winlator.cmod.saves;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;

import java.io.File;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final File[] files;
    private final OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClicked(File file);
    }

    public FileAdapter(File[] files, OnFileClickListener listener) {
        this.files = files;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Neon row instead of the stock simple_list_item_1 - see
        // neon_file_picker_item.xml for why.
        // 기본 simple_list_item_1 대신 네온 행을 씀 - 이유는
        // neon_file_picker_item.xml 참고
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.neon_file_picker_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = files[position];
        holder.textView.setText(displayName(file));
        // Directories and files now look different, which the old
        // single-line stock row had no way to show.
        // 폴더와 파일이 이제 구분되어 보임. 기존 기본 행에는 그걸 보여줄
        // 방법이 없었음
        holder.iconView.setImageResource(file.isDirectory()
                ? R.drawable.icon_folder
                : R.drawable.icon_file_generic);
        holder.itemView.setOnClickListener(v -> listener.onFileClicked(file));
    }

    // Drive links in dosdevices ("d:", "y:"...) are shown in upper case, and
    // the whole-storage letters get a label: Y: (Internal), X:/W:/V: (External).
    // D:/E: (EZlator folders) are left unlabelled on purpose
    // dosdevices의 드라이브 링크("d:", "y:"...)는 대문자로 보여 주고, 저장소 전체
    // 문자에는 이름을 붙임: Y: (Internal), X:/W:/V: (External).
    // D:/E:(EZlator 폴더)는 일부러 이름을 붙이지 않음
    private static String displayName(File file) {
        String name = file.getName();
        if (!name.matches("[a-z]:")) return name;
        File parent = file.getParentFile();
        if (parent == null || !"dosdevices".equals(parent.getName())) return name;
        String upper = name.toUpperCase(java.util.Locale.ENGLISH);
        switch (name.charAt(0)) {
            case 'y': return upper + " (Internal)";
            case 'x': case 'w': case 'v': return upper + " (External)";
            default: return upper;
        }
    }

    @Override
    public int getItemCount() {
        return files.length;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ImageView iconView;

        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
            iconView = itemView.findViewById(R.id.IVFileIcon);
        }
    }
}
