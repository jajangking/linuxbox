package com.linuxbox.distro;

/** Sink progress/log sederhana untuk tugas panjang (download, extract, backup). */
public interface TaskLog {
    void log(String line);
}
