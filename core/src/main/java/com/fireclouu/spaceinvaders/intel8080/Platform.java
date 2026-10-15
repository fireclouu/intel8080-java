package com.fireclouu.spaceinvaders.intel8080;

import java.io.InputStream;
import java.util.Map;

public abstract class Platform {
    private final boolean isFileTestSuite;
    private final int[] mediaIds = new int[9];
    private boolean isDebugging = false;
    private int idMediaPlayed;
    private String filePath;
    
    private Thread emuThread;
    private final Guest guest;
    private final Emulator emulator;
    private final Inputs inputs;

    public abstract void draw(short[] memoryVideoRam);
    public abstract void stopSound(int id);
    public abstract void vibrate(long milli);
    public abstract void writeLog(String message);
    public abstract void log(Exception e, String message);
    public abstract void saveHighScoreOnPlatform(int data);
    public abstract int playMedia(int id, int loop, int priority);
    public abstract int fetchHighScoreOnPlatform();
    public abstract void sendNotification(String message);

    public abstract InputStream openFile(String romName);
    public abstract String getTestAssetPath();
    public abstract int getMediaAudioIdAlienKilled();
    public abstract int getMediaAudioIdAlienMove1();
    public abstract int getMediaAudioIdAlienMove2();
    public abstract int getMediaAudioIdAlienMove3();
    public abstract int getMediaAudioIdAlienMove4();
    public abstract int getMediaAudioIdFire();
    public abstract int getMediaAudioIdPlayerExploded();
    public abstract int getMediaAudioIdShipHit();
    public abstract int getMediaAudioIdShipIncoming();
    public abstract void initMediaHandler();
    public abstract void releaseResources();
    public abstract void showDebug();

    public Platform(boolean isFileTestSuite) {
        this.isFileTestSuite = isFileTestSuite;
        this.guest = new Guest(this);
        this.emulator = new Emulator(guest);
        this.inputs = new Inputs(emulator);
    }

    public void start() {
        // cant remove for now, was from extended class
        initMediaHandler();

        // media
        setMediaId(Guest.Media.Audio.ALIEN_KILLED, getMediaAudioIdAlienKilled());
        setMediaId(Guest.Media.Audio.ALIEN_MOVE_1, getMediaAudioIdAlienMove1());
        setMediaId(Guest.Media.Audio.ALIEN_MOVE_2, getMediaAudioIdAlienMove2());
        setMediaId(Guest.Media.Audio.ALIEN_MOVE_3, getMediaAudioIdAlienMove3());
        setMediaId(Guest.Media.Audio.ALIEN_MOVE_4, getMediaAudioIdAlienMove4());
        setMediaId(Guest.Media.Audio.FIRE, getMediaAudioIdFire());
        setMediaId(Guest.Media.Audio.PLAYER_EXPLODED, getMediaAudioIdPlayerExploded());
        setMediaId(Guest.Media.Audio.SHIP_HIT, getMediaAudioIdShipHit());
        setMediaId(Guest.Media.Audio.SHIP_INCOMING, getMediaAudioIdShipIncoming());

        boolean isLoaded = true;
        if (isFileTestSuite) {
            isLoaded = loadFile(this.filePath, 0x100);
            guest.getMmu().writeTestSuitePatch();
            guest.getCpu().setPC(0x100);
        } else {
            for (Map.Entry<String, Integer> item : Guest.mapFileData.entrySet()) {
                isLoaded &= loadFile( "/assets/" + item.getKey(), item.getValue());
            }
            guest.getCpu().setPC(0x0000);
        }

        // on a blank memory the cpu executes NOPs until it walks off the address space
        if (!isLoaded) {
            writeLog("Error: program not loaded, emulation aborted.");
            return;
        }

        emuThread = new Thread(() -> {
            while(emulator.isRunning()) {
                if (emulator.isPaused()) continue;
                if (isDebugging) showDebug();

                if (isFileTestSuite) {
                    emulator.cycleWithoutTiming();
                } else {
                    emulator.tick();
                }
            }
        }, "emulator");
        emuThread.start();
    }

    private boolean loadFile(String filePath, int startAddress) {
        try (InputStream in = getClass().getResourceAsStream(filePath)) {
            if (in == null) {
                writeLog("Error: file not found: " + filePath);
                return false;
            }

            short read;
            while((read = (short) in.read()) != -1) {
                guest.getMmu().load(startAddress++, read);
            }
            return true;
        } catch (Exception e) {
            log(e, "Error reading " + filePath);
        }
        return false;
    }

    public void sendInput(int port, byte key, boolean isDown) {
        inputs.sendInput(port, key, isDown);
    }

    public byte getPlayerPort() {
        return inputs.getPlayerPort();
    }

    public void setPlayerPort(byte playerPort) {
        inputs.setPlayerPort(playerPort);
    }

    public void emulationPause() {
        emulator.setPause(true);
    }

    public void emulationResume() {
        emulator.setPause(false);
    }

    public void emulationTerminate() {
        releaseResources();
    }

    public void togglePause() {
        boolean pause = !emulator.isPaused();
        emulator.setPause(pause);
    }

    public boolean isFileTestSuite() {
        return this.isFileTestSuite;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setIdMediaPlayed(int idMediaPlayed) {
        this.idMediaPlayed = idMediaPlayed;
    }

    private void setMediaId(int index, int id) {
        mediaIds[index] = id;
    }

    public int getMediaId(int index) {
        return mediaIds[index];
    }

    public int getIdMediaPlayed() {
        return this.idMediaPlayed;
    }

    public boolean isDebugging() {
        return isDebugging;
    }

    public void setDebugging(boolean debugging) {
        isDebugging = debugging;
    }

    public Cpu getCpu() {
        return emulator.getCpu();
    }

    public Mmu getMmu() {
        return guest.getMmu();
    }

    public String getfilePath() {
        return filePath;
    }
}
