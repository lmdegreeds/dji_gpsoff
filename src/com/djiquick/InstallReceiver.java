package com.djiquick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Статус сессии PackageInstaller при обновлении. На STATUS_PENDING_USER_ACTION система
 * отдаёт нам Intent подтверждения — поднимаем его, чтобы пользователь увидел штатный
 * диалог установки. Остальные статусы просто пишем в журнал: при неудаче их видно
 * в диагностике.
 */
public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(confirm); }
                catch (Throwable t) { Logger.w("[update] диалог подтверждения: " + t); }
            }
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Logger.i("[update] статус установки=" + status + (msg != null ? " " + msg : ""));
        }
    }
}
