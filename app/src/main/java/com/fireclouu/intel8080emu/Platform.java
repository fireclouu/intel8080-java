package com.fireclouu.intel8080emu;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.text.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import com.fireclouu.intel8080emu.Emulator.BaseClass.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Platform extends PlatformAdapter implements ResourceAdapter {
	private Context context;
	private SharedPreferences sp;
	private SoundPool soundPool, spShipFX;
	private SharedPreferences.Editor editor;
	private Vibrator vibrator;
	private DisplayAdapter display;
	private LinearLayout llLogs;
	private ScrollView svLogs;
	private TextView tvLog;
	private Button buttonPause;
	
	private ExecutorService exec2;
	
	public Platform(Activity activity, Context context, DisplayAdapter display, boolean isTestSuite) {
		super(display, isTestSuite);
		this.display = display;
		this.context = context;
		
		tvLog = activity.findViewById(R.id.tvLog);
		llLogs = activity.findViewById(R.id.llLogs);
		buttonPause = activity.findViewById(R.id.buttonPause);
		svLogs = activity.findViewById(R.id.svLogs);
		buttonPause.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View p1) {
					togglePause();
				}
		});
		
		tvLog.setText("");
		platformInit();
		exec2 = Executors.newSingleThreadExecutor();
		HostHook.getInstance().setPlatform(this);
	}
	
	private void platformInit() {
		soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
		spShipFX = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
		vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
		sp = context.getSharedPreferences(StringUtils.PREFS_NAME, 0);
		editor = sp.edit();
	}
	
	@Override
	public InputStream openFile(String romName) {
		try {
			return context.getAssets().open(romName);
		} catch (IOException e) {
			String exception = e.getMessage() == null ? "openFile: Message is null" : e.getMessage();
			Log.e(StringUtils.TAG, exception);
			return null;
		}
	}
	
	/////   API   /////
	@Override
	public void playSound(int id, int loop) {
		soundPool.play(id, 1, 1, 0, loop, 1);
	}

	@Override
	public void reloadResource() {
		setEffectShipIncoming(R.raw.ship_incoming);
	}

	@Override
	public void setEffectFire(int id) {
		MachineResources.MEDIA_EFFECT_FIRE = soundPool.load(context, id, 1);
	}

	@Override
	public void setEffectPlayerExploded(int id) {
		MachineResources.MEDIA_EFFECT_PLAYER_EXPLODED = soundPool.load(context, id, 1);
	}

	@Override
	public void setEffectShipIncoming(int id) {
		MachineResources.MEDIA_EFFECT_SHIP_INCOMING = spShipFX.load(context, id, 1);
	}

	@Override
	public void setEffectAlienMove(int id1, int id2, int id3, int id4) {
		MachineResources.MEDIA_EFFECT_ALIEN_MOVE_1 = soundPool.load(context, id1, 1);
		MachineResources.MEDIA_EFFECT_ALIEN_MOVE_2 = soundPool.load(context, id2, 1);
		MachineResources.MEDIA_EFFECT_ALIEN_MOVE_3 = soundPool.load(context, id3, 1);
		MachineResources.MEDIA_EFFECT_ALIEN_MOVE_4 = soundPool.load(context, id4, 1);
	}

	@Override
	public void setEffectAlienKilled(int id) {
		MachineResources.MEDIA_EFFECT_ALIEN_KILLED = soundPool.load(context, id, 1);
	}

	@Override
	public void setEffectShipHit(int id) {
		MachineResources.MEDIA_EFFECT_SHIP_HIT = soundPool.load(context, id, 1);
	}

	@Override
	public void playShipFX() {
		spShipFX.play(MachineResources.MEDIA_EFFECT_SHIP_INCOMING, 1, 1, 0, -1, 1);
	}

	@Override
	public void releaseShipFX() {
		spShipFX.stop(MachineResources.MEDIA_EFFECT_SHIP_INCOMING);
		spShipFX.unload(MachineResources.MEDIA_EFFECT_SHIP_INCOMING);
		spShipFX.release();
		initShipFX();
	}

	@Override
	public void initShipFX() {
		spShipFX = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
		reloadResource();
	}


	public void releaseResource() {
		soundPool.release();
		soundPool = null;
	}
	
	/////   SHARED PREFS   /////
	@Override
	public void putPrefs(String name, int value) {
		editor.putInt(name, value);
		editor.apply();
	}

	@Override
	public int getPrefs(String name) {
		return sp.getInt(name, 0);
	}
	
	/////   ACCESSORIES   /////
	@Override
	public void vibrate(long milli) {
		vibrator.vibrate(milli);
	}

	@Override
	public boolean isDrawing() {
		return display.isDrawing();
	}
	
	@Override
	public void writeLog(final String message) {
		handler.post(new Runnable() {
				@Override
				public void run() {
					tvLog.append(message);
					svLogs.post(new Runnable() {
							@Override
							public void run() {
								svLogs.fullScroll(View.FOCUS_DOWN);
							}
						});
				}			
		});
	}

	@Override
	public void toggleLog(boolean isLogging) {
		super.toggleLog(isLogging);
		tvLog.setText("");
	}
	
	@Override
	public void togglePause() {
		super.togglePause();
	}

	@Override
	public void start() {
		super.start();
		executor.execute(new Runnable() {
				@Override
				public void run() {
					// FIXME: terminate when pause to
					// optimize battery usage
					
					while (isLooping()) {
						if (!isPaused()) {
							if (!isTestSuite()) {
								tickEmulator();
							} else {
								tickCpuOnly();
							}
						}
					}
					
					writeLog("\n");
					for (int i = 0; i < 25; i++) {
						writeLog("-");
					}
					
					handler.post(new Runnable() {
							@Override
							public void run() {
								Toast.makeText(context, "Emulation terminated", Toast.LENGTH_SHORT).show();
							}
					});
					
					 // if (isLooping()) handler.post(this);
					// if (isLooping()) executorEmulator.execute(this);
				}
		});
	}
	
	
}
