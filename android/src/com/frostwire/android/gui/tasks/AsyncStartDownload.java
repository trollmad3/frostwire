/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2020, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.android.gui.tasks;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.frostwire.android.BuildConfig;
import com.frostwire.android.R;
import com.frostwire.android.gui.dialogs.HandpickedTorrentDownloadDialogOnFetch;
import com.frostwire.android.gui.transfers.ExistingDownload;
import com.frostwire.android.gui.transfers.InvalidDownload;
import com.frostwire.android.gui.transfers.InvalidTransfer;
import com.frostwire.android.gui.transfers.TransferManager;
import com.frostwire.android.gui.util.UIUtils;
import com.frostwire.android.offers.Offers;
import com.frostwire.search.SearchResult;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.search.torrent.TorrentSearchResult;
import com.frostwire.transfers.BittorrentDownload;
import com.frostwire.transfers.Transfer;
import com.frostwire.util.Logger;
import com.frostwire.util.Ref;

import java.lang.ref.WeakReference;

/**
 * @author gubatron
 * @author aldenml
 */
public class AsyncStartDownload {

    private static final Logger LOG = Logger.getLogger(AsyncStartDownload.class);
    private static final Handler handler;

    static {
        HandlerThread HT = new HandlerThread("AsyncStartDownload-HandlerThread");
        HT.start();
        handler = new Handler(HT.getLooper());
    }

    public static void stopHandlerThread() {
        if (handler.getLooper().getThread() instanceof HandlerThread) {
            ((HandlerThread) handler.getLooper().getThread()).quitSafely();
        }
    }

    public AsyncStartDownload(final Context ctx, final SearchResult sr, final String message) {
        //async(ctx, AsyncStartDownload::doInBackground, sr, message, AsyncStartDownload::onPostExecute);
        WeakReference<Context> ctxRef = Ref.weak(ctx);
        handler.post(() -> {
            LOG.info("AsyncStartDownload: posting to handler", true);
            try {
                if (!Ref.alive(ctxRef)) {
                    Ref.free(ctxRef);
                    return;
                }
                final Transfer transfer = doInBackground(ctxRef.get(), sr, message);
                if (transfer == null) {
                    Ref.free(ctxRef);
                    return;
                }
                Handler uiHandler = new Handler(Looper.getMainLooper());
                uiHandler.postAtFrontOfQueue(() -> {
                    if (!Ref.alive(ctxRef)) {
                        Ref.free(ctxRef);
                        return;
                    }
                    try {
                        onPostExecute(ctxRef.get(), sr, message, transfer);
                    } catch (Throwable t) {
                        LOG.error(t.getMessage(), t);
                    } finally {
                        Ref.free(ctxRef);
                    }
                });
            } catch (Throwable t) {
                LOG.error(t.getMessage(), t);
            }
        });
    }

    public AsyncStartDownload(final Context ctx, final SearchResult sr) {
        this(ctx, sr, null);
    }

    public static void submitRunnable(Runnable runnable) {
        handler.post(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOG.error("submitRunnable() failed: " + t.getMessage(), t);
            }
        });
    }

    private static Transfer doInBackground(final Context ctx, final SearchResult sr, final String message) {
        Transfer transfer = null;
        try {
            if (sr instanceof TorrentSearchResult &&
                    !(sr instanceof TorrentCrawledSearchResult)) {
                transfer = TransferManager.instance().downloadTorrent(((TorrentSearchResult) sr).getTorrentUrl(),
                        new HandpickedTorrentDownloadDialogOnFetch((Activity) ctx, false), sr.getDisplayName());
            } else {
                transfer = TransferManager.instance().download(sr);
                if (!(transfer instanceof InvalidDownload)) {
                    if (ctx instanceof Activity) {
                        ((Activity) ctx).runOnUiThread(() -> {
                            try {
                                UIUtils.showTransfersOnDownloadStart(ctx);
                            } catch (Throwable t) {
                                if (BuildConfig.DEBUG) {
                                    throw t;
                                }
                                LOG.error("doInBackground() " + t.getMessage(), t);
                            }
                        });
                    }
                }
            }
        } catch (Throwable e) {
            LOG.warn("Error adding new download from result: " + sr, e);
            e.printStackTrace();
        }
        return transfer;
    }

    private static void onPostExecute(final Context ctx, final SearchResult sr, final String message, final Transfer transfer) {
        if (transfer != null) {
            if (ctx instanceof Activity) {
                Offers.showInterstitialOfferIfNecessary((Activity) ctx, Offers.PLACEMENT_INTERSTITIAL_MAIN, false, false);
            }

            if (!(transfer instanceof InvalidTransfer)) {
                TransferManager tm = TransferManager.instance();
                if (tm.isBittorrentDownloadAndMobileDataSavingsOn(transfer)) {
                    UIUtils.showLongMessage(ctx, R.string.torrent_transfer_enqueued_on_mobile_data);
                    ((BittorrentDownload) transfer).pause();
                } else {
                    if (tm.isBittorrentDownloadAndMobileDataSavingsOff(transfer)) {
                        UIUtils.showLongMessage(ctx, R.string.torrent_transfer_consuming_mobile_data);
                    }

                    if (message != null) {
                        UIUtils.showShortMessage(ctx, message);
                    }
                }
                UIUtils.showTransfersOnDownloadStart(ctx);
            } else if (!(transfer instanceof ExistingDownload)) {
                UIUtils.showLongMessage(ctx, ((InvalidTransfer) transfer).getReasonResId());
            }
        }
    }
}
