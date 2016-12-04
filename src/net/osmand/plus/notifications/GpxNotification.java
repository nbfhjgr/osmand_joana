package net.osmand.plus.notifications;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.app.NotificationCompat.BigTextStyle;
import android.support.v4.app.NotificationCompat.Builder;
import android.support.v7.app.NotificationCompat;

import net.osmand.plus.NavigationService;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.monitoring.OsmandMonitoringPlugin;
import net.osmand.util.Algorithms;

import static net.osmand.plus.NavigationService.USED_BY_GPX;
import static net.osmand.plus.NavigationService.USED_BY_NAVIGATION;

public class GpxNotification extends OsmandNotification {

	public final static String OSMAND_SAVE_GPX_SERVICE_ACTION = "OSMAND_SAVE_GPX_SERVICE_ACTION";
	public final static String OSMAND_START_GPX_SERVICE_ACTION = "OSMAND_START_GPX_SERVICE_ACTION";
	public final static String OSMAND_STOP_GPX_SERVICE_ACTION = "OSMAND_STOP_GPX_SERVICE_ACTION";

	private boolean wasDismissed;

	public GpxNotification(OsmandApplication app) {
		super(app);
	}

	@Override
	public void init() {
		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				final OsmandMonitoringPlugin plugin = OsmandPlugin.getEnabledPlugin(OsmandMonitoringPlugin.class);
				if (plugin != null) {
					plugin.saveCurrentTrack();
				}
			}
		}, new IntentFilter(OSMAND_SAVE_GPX_SERVICE_ACTION));

		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				final OsmandMonitoringPlugin plugin = OsmandPlugin.getEnabledPlugin(OsmandMonitoringPlugin.class);
				if (plugin != null) {
					plugin.startGPXMonitoring(null);
					plugin.updateControl();
				}
			}
		}, new IntentFilter(OSMAND_START_GPX_SERVICE_ACTION));

		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				final OsmandMonitoringPlugin plugin = OsmandPlugin.getEnabledPlugin(OsmandMonitoringPlugin.class);
				if (plugin != null) {
					plugin.stopRecording();
					plugin.updateControl();
				}
			}
		}, new IntentFilter(OSMAND_STOP_GPX_SERVICE_ACTION));
	}

	@Override
	public NotificationType getType() {
		return NotificationType.GPX;
	}

	@Override
	public int getPriority() {
		return NotificationCompat.PRIORITY_DEFAULT;
	}

	@Override
	public boolean isActive() {
		NavigationService service = app.getNavigationService();
		return isEnabled()
				&& service != null
				&& (service.getUsedBy() & USED_BY_GPX) != 0;
	}

	@Override
	public boolean isEnabled() {
		return OsmandPlugin.getEnabledPlugin(OsmandMonitoringPlugin.class) != null;
	}

	@Override
	public void onNotificationDismissed() {
		wasDismissed = true;
	}

	@Override
	public Builder buildNotification() {
		if (!isEnabled()) {
			return null;
		}
		String notificationTitle;
		String notificationText;
		color = 0;
		icon = R.drawable.ic_action_polygom_dark;
		boolean isGpxRecording = app.getSavingTrackHelper().getIsRecording();
		float recordedDistane = app.getSavingTrackHelper().getDistance();
		ongoing = true;
		if (isGpxRecording) {
			color = app.getResources().getColor(R.color.osmand_orange);
			notificationTitle = app.getString(R.string.shared_string_trip) + " • "
					+ Algorithms.formatDuration((int) (app.getSavingTrackHelper().getDuration() / 1000), true);
			notificationText = app.getString(R.string.shared_string_recorded)
					+ ": " + OsmAndFormatter.getFormattedDistance(recordedDistane, app);
		} else {
			if (recordedDistane > 0) {
				notificationTitle = app.getString(R.string.shared_string_pause) + " • "
						+ Algorithms.formatDuration((int) (app.getSavingTrackHelper().getDuration() / 1000), true);
				notificationText = app.getString(R.string.shared_string_recorded)
						+ ": " + OsmAndFormatter.getFormattedDistance(recordedDistane, app);
			} else {
				ongoing = false;
				notificationTitle = app.getString(R.string.shared_string_trip_recording);
				notificationText = app.getString(R.string.gpx_logging_no_data);
			}
		}

		if ((wasDismissed || !app.getSettings().SHOW_TRIP_REC_NOTIFICATION.get()) && !ongoing) {
			return null;
		}

		final Builder notificationBuilder = createBuilder()
				.setContentTitle(notificationTitle)
				.setStyle(new BigTextStyle().bigText(notificationText));

		Intent saveIntent = new Intent(OSMAND_SAVE_GPX_SERVICE_ACTION);
		PendingIntent savePendingIntent = PendingIntent.getBroadcast(app, 0, saveIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		if (isGpxRecording) {
			Intent stopIntent = new Intent(OSMAND_STOP_GPX_SERVICE_ACTION);
			PendingIntent stopPendingIntent = PendingIntent.getBroadcast(app, 0, stopIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
			if (app.getSavingTrackHelper().getDistance() > 0) {
				notificationBuilder.addAction(R.drawable.ic_pause,
						app.getString(R.string.shared_string_pause), stopPendingIntent);
				notificationBuilder.addAction(R.drawable.ic_action_save, app.getString(R.string.shared_string_save),
						savePendingIntent);
			} else {
				notificationBuilder.addAction(R.drawable.ic_action_rec_stop,
						app.getString(R.string.shared_string_control_stop), stopPendingIntent);
			}
		} else {
			Intent startIntent = new Intent(OSMAND_START_GPX_SERVICE_ACTION);
			PendingIntent startPendingIntent = PendingIntent.getBroadcast(app, 0, startIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
			if (recordedDistane > 0) {
				notificationBuilder.addAction(R.drawable.ic_action_rec_start,
						app.getString(R.string.shared_string_continue), startPendingIntent);
				notificationBuilder.addAction(R.drawable.ic_action_save, app.getString(R.string.shared_string_save),
						savePendingIntent);
			} else {
				notificationBuilder.addAction(R.drawable.ic_action_rec_start,
						app.getString(R.string.shared_string_record), startPendingIntent);
			}
		}

		return notificationBuilder;
	}

	@Override
	public int getUniqueId() {
		return GPX_NOTIFICATION_SERVICE_ID;
	}
}
