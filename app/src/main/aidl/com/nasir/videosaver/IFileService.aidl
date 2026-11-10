package com.nasir.videosaver;

import android.os.ParcelFileDescriptor;

interface IFileService {
    void destroy() = 16777114;
    String[] scan(String root) = 1;
    ParcelFileDescriptor open(String path) = 2;
    String info(String root) = 3;
}
