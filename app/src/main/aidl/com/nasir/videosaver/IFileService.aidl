package com.nasir.videosaver;

import android.os.ParcelFileDescriptor;

interface IFileService {
    void destroy() = 16777114;
    String[] scanAll() = 1;
    ParcelFileDescriptor open(String path) = 2;
    String info(String root) = 3;
    String progress() = 4;
    void cancel() = 5;
}
