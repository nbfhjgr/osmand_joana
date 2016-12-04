package net.osmand.plus.routing;


import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.PointDescription;
import net.osmand.plus.ApplicationMode;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.helpers.WaypointHelper.LocationPointWrapper;
import net.osmand.plus.routing.AlarmInfo.AlarmInfoType;
import net.osmand.plus.routing.RouteCalculationResult.NextDirectionInfo;
import net.osmand.plus.voice.AbstractPrologCommandPlayer;
import net.osmand.plus.voice.CommandBuilder;
import net.osmand.plus.voice.CommandPlayer;
import net.osmand.router.RouteSegmentResult;
import net.osmand.router.TurnType;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;
import alice.tuprolog.Struct;
import alice.tuprolog.Term;
import android.media.AudioManager;
import android.media.SoundPool;


public class VoiceRouter {
	private static final int STATUS_UTWP_TOLD = -1;
	private static final int STATUS_UNKNOWN = 0;
	private static final int STATUS_LONG_PREPARE = 1;
	private static final int STATUS_PREPARE = 2;
	private static final int STATUS_TURN_IN = 3;
	private static final int STATUS_TURN = 4;
	private static final int STATUS_TOLD = 5;
	
	private final RoutingHelper router;
	private static CommandPlayer player;
	private final OsmandSettings settings;

	private static boolean mute = false;
	private static int currentStatus = STATUS_UNKNOWN;
	private static boolean playedAndArriveAtTarget = false;
	private static float playGoAheadDist = 0;
	private static long lastAnnouncedSpeedLimit = 0;
	private static long waitAnnouncedSpeedLimit = 0;
	private static long lastAnnouncedOffRoute = 0;
	private static long waitAnnouncedOffRoute = 0;
	private static boolean suppressDest = false;
	private static boolean announceBackOnRoute = false;
	// private static long lastTimeRouteRecalcAnnounced = 0;
	// Remember when last announcement was made
	private static long lastAnnouncement = 0;

	// Default speed to have comfortable announcements (Speed in m/s)
	protected float DEFAULT_SPEED = 12;
	protected float TURN_DEFAULT_SPEED = 5;
		
	protected int PREPARE_LONG_DISTANCE = 0;
	protected int PREPARE_LONG_DISTANCE_END = 0;
	protected int PREPARE_DISTANCE = 0;
	protected int PREPARE_DISTANCE_END = 0;
	protected int TURN_IN_DISTANCE = 0;
	protected int TURN_IN_DISTANCE_END = 0;
	protected int TURN_DISTANCE = 0;
	
	protected static VoiceCommandPending pendingCommand = null;
	private static RouteDirectionInfo nextRouteDirection;
	private Term empty;

	public interface VoiceMessageListener {
		void onVoiceMessage();
	}

	private ConcurrentHashMap<VoiceMessageListener, Integer> voiceMessageListeners;
    
	public VoiceRouter(RoutingHelper router, final OsmandSettings settings) {
		this.router = router;
		this.settings = settings;
		this.mute = settings.VOICE_MUTE.get();
		empty = new Struct("");
		voiceMessageListeners = new ConcurrentHashMap<VoiceRouter.VoiceMessageListener, Integer>();
	}
	
	public void setPlayer(CommandPlayer player) {
		this.player = player;
		if (pendingCommand != null && player != null) {
			CommandBuilder newCommand = getNewCommandPlayerToPlay();
			if (newCommand != null) {
				pendingCommand.play(newCommand);
			}
			pendingCommand = null;
		}
	}

	public CommandPlayer getPlayer() {
		return player;
	}
	
	public void setMute(boolean mute) {
		this.mute = mute;
	}
	
	public boolean isMute() {
		return mute;
	}

	protected CommandBuilder getNewCommandPlayerToPlay() {
		if (player == null) {
			return null;
		}
		lastAnnouncement = System.currentTimeMillis();
		return player.newCommandBuilder();
	}

	public void updateAppMode() {
		// Turn prompt starts either at distance, or additionally (TURN_IN and TURN only) if actual-lead-time(currentSpeed) < maximum-lead-time(defined by default speed)
		if (router.getAppMode().isDerivedRoutingFrom(ApplicationMode.CAR)) {
			PREPARE_LONG_DISTANCE = 3500;             // [105 sec @ 120 km/h]
			// Issue 1411: Do not play prompts for PREPARE_LONG_DISTANCE, not needed.
			PREPARE_LONG_DISTANCE_END = 3000 + 1000;  // [ 90 sec @ 120 km/h]
			PREPARE_DISTANCE = 1500;                  // [125 sec]
			PREPARE_DISTANCE_END = 1200;      	  // [100 sec]
			TURN_IN_DISTANCE = 300;			  //   23 sec
			TURN_IN_DISTANCE_END = 210;               //   16 sec
			TURN_DISTANCE = 50;                       //    7 sec
			TURN_DEFAULT_SPEED = 7f;                  //   25 km/h
			DEFAULT_SPEED = 13;                       //   48 km/h
		} else if (router.getAppMode().isDerivedRoutingFrom(ApplicationMode.BICYCLE)) {
			PREPARE_LONG_DISTANCE = 500;              // [100 sec]
			// Do not play:
			PREPARE_LONG_DISTANCE_END = 300 + 1000;   // [ 60 sec]
			PREPARE_DISTANCE = 200;                   // [ 40 sec]
			PREPARE_DISTANCE_END = 120;               // [ 24 sec]
			TURN_IN_DISTANCE = 80;                    //   16 sec
			TURN_IN_DISTANCE_END = 60;                //   12 sec
			TURN_DISTANCE = 30;                       //    6 sec. Check if this works with GPS accuracy!
			TURN_DEFAULT_SPEED = DEFAULT_SPEED = 5;   //   18 km/h
		} else if (router.getAppMode().isDerivedRoutingFrom(ApplicationMode.PEDESTRIAN)) {
			// prepare_long_distance warning not needed for pedestrian, but for goAhead prompt
			PREPARE_LONG_DISTANCE = 500;
			// Do not play:
			PREPARE_LONG_DISTANCE_END = 300 + 300;
			// Prepare distance is not needed for pedestrian
			PREPARE_DISTANCE = 200;                    // [100 sec]
			// Do not play:
			PREPARE_DISTANCE_END = 150 + 100;          // [ 75 sec]
			TURN_IN_DISTANCE = 50;                     //   25 sec
			TURN_IN_DISTANCE_END = 30;                 //   15 sec
			TURN_DISTANCE = 15;                        //   7,5sec. Check if this works with GPS accuracy!
			TURN_DEFAULT_SPEED = DEFAULT_SPEED = 2f;   //   7,2 km/h
		} else {
			DEFAULT_SPEED = router.getAppMode().getDefaultSpeed();
			TURN_DEFAULT_SPEED = DEFAULT_SPEED / 2;
			PREPARE_LONG_DISTANCE = (int) (DEFAULT_SPEED * 270);
			// Do not play:
			PREPARE_LONG_DISTANCE_END = (int) (DEFAULT_SPEED * 230) * 2;
			PREPARE_DISTANCE = (int) (DEFAULT_SPEED * 115);
			PREPARE_DISTANCE_END = (int) (DEFAULT_SPEED * 92);
			TURN_IN_DISTANCE = (int) (DEFAULT_SPEED * 23);
			TURN_IN_DISTANCE_END = (int) (DEFAULT_SPEED * 16);
			TURN_DISTANCE = (int) (DEFAULT_SPEED * 7);
		}
	}

	private double btScoDelayDistance = 0;

	public boolean isDistanceLess(float currentSpeed, double dist, double etalon, float defSpeed) {
		if (defSpeed <= 0) {
			defSpeed = DEFAULT_SPEED;
		}
		if (currentSpeed <= 0) {
			currentSpeed = DEFAULT_SPEED;
		}

		// Trigger close prompts earlier if delayed for BT SCO connection establishment
		if ((settings.AUDIO_STREAM_GUIDANCE.get() == 0) && !AbstractPrologCommandPlayer.btScoStatus) {
			btScoDelayDistance = currentSpeed * (double) settings.BT_SCO_DELAY.get() / 1000;
		}

		if ((dist < etalon + btScoDelayDistance) || ((dist - btScoDelayDistance) / currentSpeed) < (etalon / defSpeed)) {
			return true;
		}
		return false;
	}

	public int calculateImminent(float dist, Location loc) {
		float speed = DEFAULT_SPEED;
		if (loc != null && loc.hasSpeed()) {
			speed = loc.getSpeed();
		}
		if (isDistanceLess(speed, dist, TURN_DISTANCE, 0f)) {
			return 0;
		} else if (dist <= PREPARE_DISTANCE) {
			return 1;
		} else if (dist <= PREPARE_LONG_DISTANCE) {
			return 2;
		} else {
			return -1;
		}
	}

	private void nextStatusAfter(int previousStatus) {
		//STATUS_UNKNOWN=0 -> STATUS_LONG_PREPARE=1 -> STATUS_PREPARE=2 -> STATUS_TURN_IN=3 -> STATUS_TURN=4 -> STATUS_TOLD=5
		if (previousStatus != STATUS_TOLD) {
			this.currentStatus = previousStatus + 1;
		} else {
			this.currentStatus = previousStatus;
		}
	}
	
	private boolean statusNotPassed(int statusToCheck) {
		return currentStatus <= statusToCheck;
	}
	
	public void announceOffRoute(double dist) {
		long ms = System.currentTimeMillis();
		if (waitAnnouncedOffRoute == 0 || ms - lastAnnouncedOffRoute > waitAnnouncedOffRoute) {
			CommandBuilder p = getNewCommandPlayerToPlay();
			if (p != null) {
				notifyOnVoiceMessage();
				p.offRoute(dist).play();
				announceBackOnRoute = true;
			}
			if (waitAnnouncedOffRoute == 0) {
				waitAnnouncedOffRoute = 60000;	
			} else {
				waitAnnouncedOffRoute *= 2.5;
			}
			lastAnnouncedOffRoute = ms;
		}
	}

	public void announceBackOnRoute() {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (announceBackOnRoute == true) {
			if (p != null) {
				notifyOnVoiceMessage();
				p.backOnRoute().play();
			}
			announceBackOnRoute = false;
		}
	}

	public void approachWaypoint(Location location, List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}
		notifyOnVoiceMessage();
		double[] dist = new double[1];
		makeSound();
		String text = getText(location, points, dist);
		p.goAhead(dist[0], null).andArriveAtWayPoint(text).play();
	}

	public void approachFavorite(Location location, List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}
		notifyOnVoiceMessage();
		double[] dist = new double[1];
		makeSound();
		String text = getText(location, points, dist);
		p.goAhead(dist[0], null).andArriveAtFavorite(text).play();
	}
	
	public void approachPoi(Location location, List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}

		notifyOnVoiceMessage();
		double[] dist = new double[1];
		String text = getText(location, points,  dist);
		p.goAhead(dist[0], null).andArriveAtPoi(text).play();
	}

	public void announceWaypoint(List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}
		notifyOnVoiceMessage();
		makeSound();
		String text = getText(null, points,null);
		p.arrivedAtWayPoint(text).play();
	}
	
	public void announceFavorite(List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}
		notifyOnVoiceMessage();
		makeSound();
		String text = getText(null, points,null);
		p.arrivedAtFavorite(text).play();
	}
	
	public void announcePoi(List<LocationPointWrapper> points) {
		CommandBuilder p = getNewCommandPlayerToPlay();
		if (p == null) {
			return;
		}
		notifyOnVoiceMessage();
		String text = getText(null, points,null);
		p.arrivedAtPoi(text).play();
	}

	protected String getText(Location location, List<LocationPointWrapper> points, double[] dist) {
		String text = "";
		for (LocationPointWrapper point : points) {
			// Need to calculate distance to nearest point
			if (text.length() == 0) {
				if (location != null && dist != null) {
					dist[0] = point.getDeviationDistance() + 
							MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
									point.getPoint().getLatitude(), point.getPoint().getLongitude());
				}
			} else {
				text += ", ";
			}
			text += PointDescription.getSimpleName(point.getPoint(), router.getApplication());
		}
		return text;
	}

	public void announceAlarm(AlarmInfo info, float speed) {
		AlarmInfoType type = info.getType();
		if (type == AlarmInfoType.SPEED_LIMIT) {
			announceSpeedAlarm(info.getIntValue(), speed);
		} else if (type == AlarmInfoType.SPEED_CAMERA) {
			if (router.getSettings().SPEAK_SPEED_CAMERA.get()) {
				CommandBuilder p = getNewCommandPlayerToPlay();
				if (p != null) {
					notifyOnVoiceMessage();
					p.attention(type+"").play();
				}
			}
		} else if (type == AlarmInfoType.PEDESTRIAN) {
			if (router.getSettings().SPEAK_PEDESTRIAN.get()) {
				CommandBuilder p = getNewCommandPlayerToPlay();
				if (p != null) {
					notifyOnVoiceMessage();
					p.attention(type+"").play();
				}
			}
		} else {
			if (router.getSettings().SPEAK_TRAFFIC_WARNINGS.get()) {
				CommandBuilder p = getNewCommandPlayerToPlay();
				if (p != null) {
					notifyOnVoiceMessage();
					p.attention(type+"").play();
				}
				// See Issue 2377: Announce destination again - after some motorway tolls roads split shortly after the toll
				if (type == AlarmInfoType.TOLL_BOOTH) {
					suppressDest = false;
				}
			}
		}
	}

	public void announceSpeedAlarm(int maxSpeed, float speed) {
		long ms = System.currentTimeMillis();
		if (waitAnnouncedSpeedLimit == 0) {
			//  Wait 10 seconds before announcement
			if (ms - lastAnnouncedSpeedLimit > 120 * 1000) {
				waitAnnouncedSpeedLimit = ms;
			}	
		} else {
			// If we wait before more than 20 sec (reset counter)
			if (ms - waitAnnouncedSpeedLimit > 20 * 1000) {
				waitAnnouncedSpeedLimit = 0;
			} else if (router.getSettings().SPEAK_SPEED_LIMIT.get()  && ms - waitAnnouncedSpeedLimit > 10 * 1000 ) {
				CommandBuilder p = getNewCommandPlayerToPlay();
				if (p != null) {
					notifyOnVoiceMessage();
					lastAnnouncedSpeedLimit = ms;
					waitAnnouncedSpeedLimit = 0;
					p.speedAlarm(maxSpeed, speed).play();
				}
			}
		}
	}
	
	private boolean isTargetPoint(NextDirectionInfo info) {
		boolean in = info != null && info.intermediatePoint;
		boolean target = info == null || info.directionInfo == null
				|| info.directionInfo.distance == 0;
		return in || target;
	}

	private boolean needsInforming() {
		final Integer repeat = settings.KEEP_INFORMING.get();
		if (repeat == null || repeat == 0) return false;

		final long notBefore = lastAnnouncement + repeat * 60 * 1000L;

		return System.currentTimeMillis() > notBefore;
	}

	/**
	* Updates status of voice guidance
	* @param currentLocation
	*/
	protected void updateStatus(Location currentLocation, boolean repeat) {
		// Directly after turn: goAhead (dist), unless:
		// < PREPARE_LONG_DISTANCE (e.g. 3500m):         playPrepareTurn (-not played any more-)
		// < PREPARE_DISTANCE      (e.g. 1500m):         playPrepareTurn ("Turn after ...")
		// < TURN_IN_DISTANCE      (e.g. 390m or 30sec): playMakeTurnIn  ("Turn in ...")
		// < TURN_DISTANCE         (e.g. 50m or 7sec):   playMakeTurn    ("Turn ...")
		float speed = DEFAULT_SPEED;
		if (currentLocation != null && currentLocation.hasSpeed()) {
			speed = Math.max(currentLocation.getSpeed(), speed);
		}

		NextDirectionInfo nextInfo = router.getNextRouteDirectionInfo(new NextDirectionInfo(), true);
		RouteSegmentResult currentSegment = router.getCurrentSegmentResult();
		if (nextInfo.directionInfo == null) {
			return;
		}
		int dist = nextInfo.distanceTo;
		RouteDirectionInfo next = nextInfo.directionInfo;

		// If routing is changed update status to unknown
		if (next != nextRouteDirection) {
			nextRouteDirection = next;
			currentStatus = STATUS_UNKNOWN;
			suppressDest = false;
			playedAndArriveAtTarget = false;
			announceBackOnRoute = false;
			if (playGoAheadDist != -1) {
				playGoAheadDist = 0;
			}
		}

		if (!repeat) {
			if (dist <= 0) {
				return;
			} else if (needsInforming()) {
				playGoAhead(dist, getSpeakableStreetName(currentSegment, next, false));
				return;
			} else if (currentStatus == STATUS_TOLD) {
				// nothing said possibly that's wrong case we should say before that
				// however it should be checked manually ?
				return;
			}
		}

		if (currentStatus == STATUS_UNKNOWN) {
			// Play "Continue for ..." if (1) after route calculation no other prompt is due, or (2) after a turn if next turn is more than PREPARE_LONG_DISTANCE away
			if ((playGoAheadDist == -1) || (dist > PREPARE_LONG_DISTANCE)) {
				playGoAheadDist = dist - 3 * TURN_DISTANCE;
			}
		}

		NextDirectionInfo nextNextInfo = router.getNextRouteDirectionInfoAfter(nextInfo, new NextDirectionInfo(), true);  //I think "true" is correct here, not "!repeat"
		// Note: getNextRouteDirectionInfoAfter(nextInfo, x, y).distanceTo is distance from nextInfo, not from current position!

		// STATUS_TURN = "Turn (now)"
		if ((repeat || statusNotPassed(STATUS_TURN)) && isDistanceLess(speed, dist, TURN_DISTANCE, TURN_DEFAULT_SPEED)) {
			if (nextNextInfo.distanceTo < TURN_IN_DISTANCE_END && nextNextInfo != null) {
				playMakeTurn(currentSegment, next, nextNextInfo);
			} else {
				playMakeTurn(currentSegment, next, null);
			}
			if (!next.getTurnType().goAhead() && isTargetPoint(nextNextInfo)) {   // !goAhead() avoids isolated "and arrive.." prompt, as goAhead() is not pronounced
				if (nextNextInfo.distanceTo < TURN_IN_DISTANCE_END) {
					// Issue #2865: Ensure a distance associated with the destination arrival is always announced, either here, or in subsequent "Turn in" prompt
					// Distance fon non-straights already announced in "Turn (now)"'s nextnext  code above
					if ((nextNextInfo != null) && (nextNextInfo.directionInfo != null) && nextNextInfo.directionInfo.getTurnType().goAhead()) {
						playThen();
						playGoAhead(nextNextInfo.distanceTo, empty);
					}
					playAndArriveAtDestination(nextNextInfo);
				} else if (nextNextInfo.distanceTo < 1.2f * TURN_IN_DISTANCE_END) {
					// 1.2 is safety margin should the subsequent "Turn in" prompt not fit in amy more
					playThen();
					playGoAhead(nextNextInfo.distanceTo, empty);
					playAndArriveAtDestination(nextNextInfo);
				}
			}
			nextStatusAfter(STATUS_TURN);

		// STATUS_TURN_IN = "Turn in ..."
		} else if ((repeat || statusNotPassed(STATUS_TURN_IN)) && isDistanceLess(speed, dist, TURN_IN_DISTANCE, 0f)) {
			if (repeat || dist >= TURN_IN_DISTANCE_END) {
				if ((isDistanceLess(speed, nextNextInfo.distanceTo, TURN_DISTANCE, 0f) || nextNextInfo.distanceTo < TURN_IN_DISTANCE_END) &&
						nextNextInfo != null) {
					playMakeTurnIn(currentSegment, next, dist - (int) btScoDelayDistance, nextNextInfo.directionInfo);
				} else {
					playMakeTurnIn(currentSegment, next, dist - (int) btScoDelayDistance, null);
				}
				playGoAndArriveAtDestination(repeat, nextInfo, currentSegment);
			}
			nextStatusAfter(STATUS_TURN_IN);

		// STATUS_PREPARE = "Turn after ..."
		} else if ((repeat || statusNotPassed(STATUS_PREPARE)) && (dist <= PREPARE_DISTANCE)) {
			if (repeat || dist >= PREPARE_DISTANCE_END) {
				if (!repeat && (next.getTurnType().keepLeft() || next.getTurnType().keepRight())) {
					// Do not play prepare for keep left/right
				} else {
					playPrepareTurn(currentSegment, next, dist);
					playGoAndArriveAtDestination(repeat, nextInfo, currentSegment);
				}
			}
			nextStatusAfter(STATUS_PREPARE);

		// STATUS_LONG_PREPARE =  also "Turn after ...", we skip this now, users said this is obsolete
		} else if ((repeat || statusNotPassed(STATUS_LONG_PREPARE)) && (dist <= PREPARE_LONG_DISTANCE)) {
			if (repeat || dist >= PREPARE_LONG_DISTANCE_END) {
				playPrepareTurn(currentSegment, next, dist);
				playGoAndArriveAtDestination(repeat, nextInfo, currentSegment);
			}
			nextStatusAfter(STATUS_LONG_PREPARE);

		// STATUS_UNKNOWN = "Continue for ..." if (1) after route calculation no other prompt is due, or (2) after a turn if next turn is more than PREPARE_LONG_DISTANCE away
		} else if (statusNotPassed(STATUS_UNKNOWN)) {
			// Strange how we get here but
			nextStatusAfter(STATUS_UNKNOWN);
		} else if (repeat || (statusNotPassed(STATUS_PREPARE) && dist < playGoAheadDist)) {
			playGoAheadDist = 0;
			playGoAhead(dist, getSpeakableStreetName(currentSegment, next, false));
		}
	}

	public void announceCurrentDirection(Location currentLocation) {
		synchronized (router) {
			if (currentStatus != STATUS_UTWP_TOLD) {
				updateStatus(currentLocation, true);
			} else if (playMakeUTwp()) {
				playGoAheadDist = 0;
			}
		}
	}

	private boolean playMakeUTwp() {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.makeUTwp().play();
			return true;
		}
		return false;
	}

	private void playThen() {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.then().play();
		}
	}

	private void playGoAhead(int dist, Term streetName) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.goAhead(dist, streetName).play();
		}
	}

	public Term getSpeakableStreetName(RouteSegmentResult currentSegment, RouteDirectionInfo i, boolean includeDest) {
		if (i == null || !router.getSettings().SPEAK_STREET_NAMES.get()) {
			return empty;
		}
		if (player != null && player.supportsStructuredStreetNames()) {
			Term next = empty;
			// Issue 2377: Play Dest here only if not already previously announced, to avoid repetition
			if (includeDest == true) {
				next = new Struct(new Term[] { getTermString(getSpeakablePointName(i.getRef())),
						getTermString(getSpeakablePointName(i.getStreetName())),
						getTermString(getSpeakablePointName(i.getDestinationName())) });
			} else {
				next = new Struct(new Term[] { getTermString(getSpeakablePointName(i.getRef())),
						getTermString(getSpeakablePointName(i.getStreetName())),
						empty });
			}
			Term current = empty;
			if (currentSegment != null) {
				// Issue 2377: Play Dest here only if not already previously announced, to avoid repetition
				if (includeDest == true) {
					RouteDataObject obj = currentSegment.getObject();
					current = new Struct(new Term[] { getTermString(getSpeakablePointName(obj.getRef(currentSegment.isForwardDirection()))),
							getTermString(getSpeakablePointName(obj.getName(settings.MAP_PREFERRED_LOCALE.get(), settings.MAP_TRANSLITERATE_NAMES.get()))),
							getTermString(getSpeakablePointName(obj.getDestinationName(settings.MAP_PREFERRED_LOCALE.get(), 
									settings.MAP_TRANSLITERATE_NAMES.get(), currentSegment.isForwardDirection()))) });
				} else {
					RouteDataObject obj = currentSegment.getObject();
					current = new Struct(new Term[] { getTermString(getSpeakablePointName(obj.getRef(currentSegment.isForwardDirection()))),
							getTermString(getSpeakablePointName(obj.getName(settings.MAP_PREFERRED_LOCALE.get(),
									settings.MAP_TRANSLITERATE_NAMES.get()))),
							empty });
				}
			}
			Struct voice = new Struct("voice", next, current );
			return voice;
		} else {
			Term rf = getTermString(getSpeakablePointName(i.getRef()));
			if (rf == empty) {
				rf = getTermString(getSpeakablePointName(i.getStreetName()));
			}
			return rf;
		}
	}
	
	private Term getTermString(String s) {
		if (!Algorithms.isEmpty(s)) {
			return new Struct(s);
		}
		return empty;
	}

	public String getSpeakablePointName(String pn) {
		// Replace characters which may produce unwanted tts sounds:
		if (pn != null) {
			pn = pn.replace('-', ' ');
			pn = pn.replace(':', ' ');
			pn = pn.replace(";", ", "); // Trailing blank prevents punctuation being pronounced. Replace by comma for better intonation.
			pn = pn.replace("/", ", "); // Slash is actually pronounced by many TTS engines, ceeating an awkward voice prompt, better replace by comma.
			if ((player != null) && (!"de".equals(player.getLanguage()))) {
				pn = pn.replace("\u00df", "ss"); // Helps non-German tts voices to pronounce German Strasse (=street)
			}
			if ((player != null) && ("en".startsWith(player.getLanguage()))) {
				pn = pn.replace("SR", "S R");    // Avoid SR (as for State Route or Strada Regionale) be pronounced as "Senior" in English tts voice
			}
		}
		return pn;
	}

	private void playPrepareTurn(RouteSegmentResult currentSegment, RouteDirectionInfo next, int dist) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			String tParam = getTurnType(next.getTurnType());
			if (tParam != null) {
				notifyOnVoiceMessage();
				play.prepareTurn(tParam, dist, getSpeakableStreetName(currentSegment, next, true)).play();
			} else if (next.getTurnType().isRoundAbout()) {
				notifyOnVoiceMessage();
				play.prepareRoundAbout(dist, next.getTurnType().getExitOut(), getSpeakableStreetName(currentSegment, next, true)).play();
			} else if (next.getTurnType().getValue() == TurnType.TU || next.getTurnType().getValue() == TurnType.TRU) {
				notifyOnVoiceMessage();
				play.prepareMakeUT(dist, getSpeakableStreetName(currentSegment, next, true)).play();
			} 
		}
	}

	private void playMakeTurnIn(RouteSegmentResult currentSegment, RouteDirectionInfo next, int dist, RouteDirectionInfo pronounceNextNext) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			String tParam = getTurnType(next.getTurnType());
			boolean isPlay = true;
			if (tParam != null) {
				play.turn(tParam, dist, getSpeakableStreetName(currentSegment, next, true));
				suppressDest = true;
			} else if (next.getTurnType().isRoundAbout()) {
				play.roundAbout(dist, next.getTurnType().getTurnAngle(), next.getTurnType().getExitOut(), getSpeakableStreetName(currentSegment, next, true));
				// Other than in prepareTurn, in prepareRoundabout we do not announce destination, so we can repeat it one more time
				suppressDest = false;
			} else if (next.getTurnType().getValue() == TurnType.TU || next.getTurnType().getValue() == TurnType.TRU) {
				play.makeUT(dist, getSpeakableStreetName(currentSegment, next, true));
				suppressDest = true;
			} else {
				isPlay = false;
			}
			// 'then keep' preparation for next after next. (Also announces an interim straight segment, which is not pronounced above.)
			if (pronounceNextNext != null) {
				TurnType t = pronounceNextNext.getTurnType();
				isPlay = true;
				if (t.getValue() != TurnType.C && next.getTurnType().getValue() == TurnType.C) {
					play.goAhead(dist, getSpeakableStreetName(currentSegment, next, true));
				}
				if (t.getValue() == TurnType.TL || t.getValue() == TurnType.TSHL || t.getValue() == TurnType.TSLL
						|| t.getValue() == TurnType.TU || t.getValue() == TurnType.KL ) {
					play.then().bearLeft( getSpeakableStreetName(currentSegment, next, false));
				} else if (t.getValue() == TurnType.TR || t.getValue() == TurnType.TSHR || t.getValue() == TurnType.TSLR
						|| t.getValue() == TurnType.TRU || t.getValue() == TurnType.KR) {
					play.then().bearRight( getSpeakableStreetName(currentSegment, next, false));
				}
			}
			if (isPlay) {
				notifyOnVoiceMessage();
				play.play();
			}
		}
	}

	private void playGoAndArriveAtDestination(boolean repeat, NextDirectionInfo nextInfo, RouteSegmentResult currentSegment) {
		RouteDirectionInfo next = nextInfo.directionInfo;
		if (isTargetPoint(nextInfo) && (!playedAndArriveAtTarget || repeat)) {
			if (next.getTurnType().goAhead()) {
				playGoAhead(nextInfo.distanceTo, getSpeakableStreetName(currentSegment, next, false));
				playAndArriveAtDestination(nextInfo);
				playedAndArriveAtTarget = true;
			} else if (nextInfo.distanceTo <= 2 * TURN_IN_DISTANCE) {
				playAndArriveAtDestination(nextInfo);
				playedAndArriveAtTarget = true;
			}
		}
	}
	
	private void playAndArriveAtDestination(NextDirectionInfo info) {
		if (isTargetPoint(info)) {
			String pointName = info == null ? "" : info.pointName;
			CommandBuilder play = getNewCommandPlayerToPlay();
			if (play != null) {
				notifyOnVoiceMessage();
				if (info != null && info.intermediatePoint) {
					play.andArriveAtIntermediatePoint(getSpeakablePointName(pointName)).play();
				} else {
					play.andArriveAtDestination(getSpeakablePointName(pointName)).play();
				}
			}
		}
	}

	private void playMakeTurn(RouteSegmentResult currentSegment, RouteDirectionInfo next, NextDirectionInfo nextNextInfo) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			String tParam = getTurnType(next.getTurnType());
			boolean isplay = true;
			if (tParam != null) {
				play.turn(tParam, getSpeakableStreetName(currentSegment, next, !suppressDest));
			} else if (next.getTurnType().isRoundAbout()) {
				play.roundAbout(next.getTurnType().getTurnAngle(), next.getTurnType().getExitOut(), getSpeakableStreetName(currentSegment, next, !suppressDest));
			} else if (next.getTurnType().getValue() == TurnType.TU || next.getTurnType().getValue() == TurnType.TRU) {
				play.makeUT(getSpeakableStreetName(currentSegment, next, !suppressDest));
			// Do not announce goAheads
			//} else if (next.getTurnType().getValue() == TurnType.C)) {
			//	play.goAhead();
			} else {
				isplay = false;
			}
			// Add turn after next
			if ((nextNextInfo != null) && (nextNextInfo.directionInfo != null)) {

				// This case only needed should we want a prompt at the end of straight segments (equivalent of makeTurn) when nextNextInfo should be announced again there.
				if (nextNextInfo.directionInfo.getTurnType().getValue() != TurnType.C && next.getTurnType().getValue() == TurnType.C) {
					play.goAhead();
					isplay = true;
				}

				String t2Param = getTurnType(nextNextInfo.directionInfo.getTurnType());
				if (t2Param != null) {
					if (isplay) {
						play.then();
						play.turn(t2Param, nextNextInfo.distanceTo, empty);
					}
				} else if (nextNextInfo.directionInfo.getTurnType().isRoundAbout()) {
					if (isplay) {
						play.then();
						play.roundAbout(nextNextInfo.distanceTo, nextNextInfo.directionInfo.getTurnType().getTurnAngle(), nextNextInfo.directionInfo.getTurnType().getExitOut(), empty);
					}
				} else if (nextNextInfo.directionInfo.getTurnType().getValue() == TurnType.TU) {
					if (isplay) {
						play.then();
						play.makeUT(nextNextInfo.distanceTo, empty);
					}
				}
			}
			if (isplay) {
				notifyOnVoiceMessage();
				play.play();
			}
		}
	}
	
	private String getTurnType(TurnType t) {
		if (TurnType.TL == t.getValue()) {
			return AbstractPrologCommandPlayer.A_LEFT;
		} else if (TurnType.TSHL == t.getValue()) {
			return AbstractPrologCommandPlayer.A_LEFT_SH;
		} else if (TurnType.TSLL == t.getValue()) {
			return AbstractPrologCommandPlayer.A_LEFT_SL;
		} else if (TurnType.TR == t.getValue()) {
			return AbstractPrologCommandPlayer.A_RIGHT;
		} else if (TurnType.TSHR == t.getValue()) {
			return AbstractPrologCommandPlayer.A_RIGHT_SH;
		} else if (TurnType.TSLR == t.getValue()) {
			return AbstractPrologCommandPlayer.A_RIGHT_SL;
		} else if (TurnType.KL == t.getValue()) {
			return AbstractPrologCommandPlayer.A_LEFT_KEEP;
		} else if (TurnType.KR == t.getValue()) {
			return AbstractPrologCommandPlayer.A_RIGHT_KEEP;
		}
		return null;
	}
	
	public void gpsLocationLost() {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.gpsLocationLost().play();
		}
	}
	
	public void gpsLocationRecover() {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.gpsLocationRecover().play();
		}
	}

	public void newRouteIsCalculated(boolean newRoute) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			if (!newRoute) {
				play.routeRecalculated(router.getLeftDistance(), router.getLeftTime()).play();
			} else {
				play.newRouteCalculated(router.getLeftDistance(), router.getLeftTime()).play();
			}
		} else if (player == null) {
			pendingCommand = new VoiceCommandPending(!newRoute ? VoiceCommandPending.ROUTE_RECALCULATED : VoiceCommandPending.ROUTE_CALCULATED, this);
		}
		if (newRoute) {
			playGoAheadDist = -1;
		}
		currentStatus = STATUS_UNKNOWN;
		suppressDest = false;
		nextRouteDirection = null;
	}

	public void arrivedDestinationPoint(String name) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.arrivedAtDestination(getSpeakablePointName(name)).play();
		}
	}
	
	public void arrivedIntermediatePoint(String name) {
		CommandBuilder play = getNewCommandPlayerToPlay();
		if (play != null) {
			notifyOnVoiceMessage();
			play.arrivedAtIntermediatePoint(getSpeakablePointName(name)).play();
		}
	}

	// This is not needed, used are only arrivedIntermediatePoint (for points on the route) or announceWaypoint (for points near the route=)
	//public void arrivedWayPoint(String name) {
	//	CommandBuilder play = getNewCommandPlayerToPlay();
	//	if (play != null) {
	//		notifyOnVoiceMessage();
	//		play.arrivedAtWayPoint(getSpeakablePointName(name)).play();
	//	}
	//}

	public void onApplicationTerminate() {
		if (player != null) {
			player.clear();
		}
	}

	public void interruptRouteCommands() {
		if (player != null) {
			player.stop();
		}
	}

	/**
	 * Command to wait until voice player is initialized 
	 */
	private class VoiceCommandPending {
		public static final int ROUTE_CALCULATED = 1;
		public static final int ROUTE_RECALCULATED = 2;
		protected final int type;
		private final VoiceRouter voiceRouter;
		
		public VoiceCommandPending(int type, VoiceRouter voiceRouter) {
			this.type = type;
			this.voiceRouter = voiceRouter;
		}

		public void play(CommandBuilder newCommand) {
			int left = voiceRouter.router.getLeftDistance();
			int time = voiceRouter.router.getLeftTime();
			if (left > 0) {
				if (type == ROUTE_CALCULATED) {
					notifyOnVoiceMessage();
					newCommand.newRouteCalculated(left, time).play();
				} else if (type == ROUTE_RECALCULATED) {
					notifyOnVoiceMessage();
					newCommand.routeRecalculated(left, time).play();
				}
			}
		}
	}

	private void makeSound() {
		if (isMute()) {
			return;
		}
		SoundPool sp = new SoundPool(5, AudioManager.STREAM_MUSIC, 0);
		int soundClick = -1;
		boolean success = true;
		try {
			// Taken unaltered from https://freesound.org/people/Corsica_S/sounds/91926/ under license http://creativecommons.org/licenses/by/3.0/ :
			soundClick = sp.load(settings.getContext().getAssets().openFd("sounds/ding.ogg"), 1);
		} catch (IOException e) {
			e.printStackTrace();
			success = false;
		}
		if (success) {
			sp.play(soundClick, 1 ,1, 0, 0, 1);
		}
	}

	public void addVoiceMessageListener(VoiceMessageListener voiceMessageListener) {
		voiceMessageListeners.put(voiceMessageListener, 0);
	}
	
	public void removeVoiceMessageListener(VoiceMessageListener voiceMessageListener) {
		voiceMessageListeners.remove(voiceMessageListener);
	}

	public void notifyOnVoiceMessage() {
		if (settings.WAKE_ON_VOICE_INT.get() > 0) {
			router.getApplication().runInUIThread(new Runnable() {
				@Override
				public void run() {
					for (VoiceMessageListener lnt : voiceMessageListeners.keySet()) {
						lnt.onVoiceMessage();
					}
				}
			});
		}
	}
}
