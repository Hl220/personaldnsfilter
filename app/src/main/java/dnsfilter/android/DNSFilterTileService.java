package dnsfilter.android;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.IOException;

import dnsfilter.ConfigurationAccess;
import util.Logger;

/**
 * Quick Settings Tile Service for toggling DNS filtering
 */
public class DNSFilterTileService extends TileService {

    private static final String TAG = "DNSFilterTileService";
    
    // Instance reference for static access
    private static DNSFilterTileService INSTANCE;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        Log.d(TAG, "Tile added");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (INSTANCE == this) {
            INSTANCE = null;
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        // Toggle the VPN service itself (enable/disable), rather than
        // opening the app or toggling pause/resume filter mode.
        try {
            String passwd = ConfigurationAccess.getLocal().getConfig().getProperty("passcode","").trim();
            if (!passwd.equals("")) {
                Logger.getLogger().logLine("Service tile action not allowed when passcode protected!");
                Logger.getLogger().message("Not permitted - Passcode protected!");
                return;
            }

            if (DNSFilterService.INSTANCE != null) {
                // VPN currently running -> disable it
                DNSFilterService.stop(false);
                updateTile();
            } else {
                // VPN currently stopped -> enable it directly
                enableVpn();
            }
        } catch (IOException e) {
            Logger.getLogger().logLine("Error toggling DNS filtering state: " + e.getMessage());
            Log.e(TAG, "Error toggling DNS filtering state", e);
        }
    }

    /**
     * Starts the VPN/DNS filter service directly, without opening the app UI.
     * Android requires one-time user consent for VPN usage (VpnService.prepare()).
     * If that consent was already granted previously, the service starts
     * immediately from the tile. If not, the system VPN consent dialog is
     * shown directly (not the app) - consent only has to be granted once.
     */
    private void enableVpn() {
        try {
            boolean vpnInAdditionToProxyMode = Boolean.parseBoolean(
                    ConfigurationAccess.getLocal().getConfig().getProperty("vpnInAdditionToProxyMode", "false"));
            boolean vpnDisabled = !vpnInAdditionToProxyMode &&
                    Boolean.parseBoolean(ConfigurationAccess.getLocal().getConfig().getProperty("dnsProxyOnAndroid", "false"));

            Intent prepareIntent = null;
            if (!vpnDisabled)
                prepareIntent = VpnService.prepare(getApplicationContext());

            if (prepareIntent == null) {
                // Already prepared (or VPN disabled/using proxy mode) - start service directly
                startFilterService();
            } else {
                // One-time VPN consent required - show the system consent dialog directly
                if (Build.VERSION.SDK_INT < 34) {
                    prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivityAndCollapse(prepareIntent);
                } else {
                    startActivityAndCollapse(
                            PendingIntent.getActivity(
                                    this,
                                    0,
                                    prepareIntent,
                                    PendingIntent.FLAG_IMMUTABLE
                            )
                    );
                }
                Logger.getLogger().message("Grant VPN permission, then tap the tile again to enable.");
            }
        } catch (NullPointerException e) {
            // NullPointer might occur on very old Android when VPN already initialized
            startFilterService();
        } catch (Exception e) {
            Logger.getLogger().logException(e);
        }
    }

    private void startFilterService() {
        Intent svcIntent = new Intent(this, DNSFilterService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        updateTile();
    }

    /**
     * Update the tile status from outside the service
     * This should be called whenever the filtering state changes
     */
    public static void requestTileUpdate(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ComponentName componentName = new ComponentName(context, DNSFilterTileService.class);
                TileService.requestListeningState(context, componentName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request tile update", e);
            }
        } else if (INSTANCE != null) {
            INSTANCE.updateTile();
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            boolean active = false;
            
            // Check if service is running and get filtering status
            if (DNSFilterService.INSTANCE != null) {
                active = DNSFilterService.INSTANCE.isFilterActive();
            }

            // Update tile state and icon
            if (active) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setIcon(Icon.createWithResource(this, R.drawable.icon));
                //tile.setLabel(getResources().getString(R.string.notificationActive));
            } else {
                tile.setState(Tile.STATE_INACTIVE); 
                tile.setIcon(Icon.createWithResource(this, R.drawable.icon_disabled));
                //tile.setLabel(getResources().getString(R.string.notificationPaused));
            }
            
            // Update the tile
            tile.updateTile();
        }
    }
} 