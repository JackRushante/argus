package dev.argus.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IPrivilegedShellService {
    Bundle execute(in String[] command, long timeoutMillis, int maxOutputBytes) = 1;
    Bundle executeToFile(
        in String[] command,
        in ParcelFileDescriptor stdoutDestination,
        long timeoutMillis,
        int maxOutputBytes
    ) = 2;
    int uid() = 3;
    void destroy() = 16777114;
}
