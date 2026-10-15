package com.fireclouu.spaceinvaders.intel8080;


import java.time.LocalDateTime;

public class Emulator {
    private final Platform platform;

    private boolean isVBlankServiced = false;
    private final short[] keyPort = new short[7];
    private final short[] lastPortValue = new short[6];
    // states
    private boolean isRunning = true;
    private boolean isPaused = false;
    private final Cpu cpu;
    private final Guest guest;
    private final Mmu mmu;

    // I/O
    private short shiftLsb;
    private short shiftMsb;
    private byte shiftOffset = 0;

    private long cyclePerSecond;

    private final long programTimeEpoch;
    private long cpuLastTime;
    private long frameLastTime;
    private long nextTimeExecution = 0;

    // system
    private long systemLastTime;
    private long systemGuestCycleTotal;

    // from what im getting,  SI runs arount 2MHz
    // we target a 60fps,
    // and since we need to determine a per frame cycle, we divide 2MHz to 60fps
    // and we get the cycle half due to mid screen vblanking 
    private static final int CPU_HZ = 2_000_000;
    private static final int FPS    = 60;
    private static final int CYCLES_PER_FRAME = CPU_HZ / FPS;
    private static final int CYCLES_HALF      = CYCLES_PER_FRAME / 2;
    // this is to compensate over cycles
    private int cycleOveshoot = 0;

    private static final long NANO_ONE_SECOND = 1_000_000_000L / FPS;  // 16_666_666
    private long nextFrameTime = System.nanoTime();

    public Emulator(Guest guest) {
        this.guest = guest;
        this.platform = guest.getPlatform();
        this.cpu = guest.getCpu();
        this.mmu = guest.getMmu();
        this.programTimeEpoch = System.nanoTime();

        cyclePerSecond = 0;

        // test suites intercept their own I/O in cycleWithoutTiming()
        if (!platform.isFileTestSuite()) {
            cpu.setIoHandler(new Cpu.IoHandler() {
                @Override
                public short in(int port) {
                    return handleIn((short) port);
                }

                @Override
                public void out(int port, short value) {
                    handleOut((short) port, value);
                }
            });
        }
    }

    public void tick() {
        if (isPaused) return;
        long now = System.nanoTime();
        if (now < nextFrameTime) return;

        runFrame();
        platform.draw(guest.getMemoryVram());

        nextFrameTime += NANO_ONE_SECOND;
        if (now - nextFrameTime > NANO_ONE_SECOND * 4) {
            nextFrameTime = now + NANO_ONE_SECOND;
        }
    }

    // timing fixes
    private void runFrame() {
        int spent = runCycles(CYCLES_HALF - cycleOveshoot);
        cpu.requestInterrupt(0x08);

        spent += runCycles(CYCLES_PER_FRAME - spent);
        cpu.requestInterrupt(0x10);

        cycleOveshoot = spent - CYCLES_PER_FRAME;
    }

    private int runCycles(int budget) {
        int spent = 0;
        while (spent < budget) {
            spent += cpu.step();
        }
        return spent;
    }

    private short handleIn(short mode) {
        short value = 0;
        switch (mode) {
            case 0: // ?
                break;
            case 1: // input
                value = keyPort[Inputs.INPUT_PORT_1];
                break;
            case 2: // input
                value = keyPort[Inputs.INPUT_PORT_2];
                break;
            case 3: // rotate shift register
                value = (short) ((((shiftMsb << 8) | shiftLsb) >> (8 - shiftOffset)) & 0xff);
                break;
        }
        return value;
    }

    private void handleOut(short port, short value) {
        switch (port) {
            case 2: // shift amount
                shiftOffset = (byte) (value & 0x7);
                break;
            case 3: // sound
                if (value != lastPortValue[3]) {
                    if ((value & 0x1) > 0 && (lastPortValue[3] & 0x1) == 0) {
                        int id = platform.playMedia(Guest.Media.Audio.SHIP_INCOMING, -1, 1);
                        platform.setIdMediaPlayed(id);
                    } else if ((value & 0x1) == 0 && (lastPortValue[3] & 0x1) > 0) {
                        platform.stopSound(platform.getIdMediaPlayed());
                    }

                    if ((value & 0x2) > 0 && (lastPortValue[3] & 0x2) == 0) {
                        platform.playMedia(Guest.Media.Audio.FIRE, 0, 0);
                    }

                    if ((value & 0x4) > 0 && (lastPortValue[3] & 0x4) == 0) {
                        platform.playMedia(Guest.Media.Audio.PLAYER_EXPLODED, 0, 0);
                        platform.vibrate(300);
                    }

                    if ((value & 0x8) > 0 && (lastPortValue[3] & 0x8) == 0) {
                        platform.playMedia(Guest.Media.Audio.ALIEN_KILLED, 0, 0);
                        platform.vibrate(20);
                    }

                    lastPortValue[3] = value;
                }
                break;
            case 4:
                shiftLsb = shiftMsb;
                shiftMsb = value;
                break;
            case 5:
                // alien moving sound
                if (value != lastPortValue[5]) {
                    if ((value & 0x1) > 0 && (lastPortValue[5] & 0x1) == 0) {
                        platform.playMedia(Guest.Media.Audio.ALIEN_MOVE_1, 0, 0);
                    }

                    if ((value & 0x2) > 0 && (lastPortValue[5] & 0x2) == 0) {
                        platform.playMedia(Guest.Media.Audio.ALIEN_MOVE_2, 0, 0);
                    }

                    if ((value & 0x4) > 0 && (lastPortValue[5] & 0x4) == 0) {
                        platform.playMedia(Guest.Media.Audio.ALIEN_MOVE_3, 0, 0);
                    }

                    if ((value & 0x8) > 0 && (lastPortValue[5] & 0x8) == 0) {
                        platform.playMedia(Guest.Media.Audio.ALIEN_MOVE_4, 0, 0);
                    }

                    if ((value & 0x10) > 0 && (lastPortValue[5] & 0x10) == 0) {
                        platform.playMedia(Guest.Media.Audio.SHIP_HIT, 0, 0);
                        platform.vibrate(800);
                    }

                    lastPortValue[5] = value;
                }
        }
    }

    public void cycle() {
        long currentTime = getRelativeTimeEpoch();
        long frameElapsedTime = currentTime - frameLastTime;
        long cpuElapsedTime = currentTime - cpuLastTime;
        long systemCurrentTime = getRelativeTimeEpoch();

        // frame timings
        if (cpu.hasInterrupt()) {
            // Timer and interrupts
            long FRAME_INTERVAL_VBLANK = 8_330_000L;
            long FRAME_INTERVAL_END = 16_670_000L;
            if (frameElapsedTime >= FRAME_INTERVAL_VBLANK && !isVBlankServiced) {
                cpu.requestInterrupt(0x08);
                isVBlankServiced = true;
            } else if (frameElapsedTime >= FRAME_INTERVAL_END) {
                cpu.requestInterrupt(0x10);
                platform.draw(guest.getMemoryVram());
                frameLastTime = currentTime;
                isVBlankServiced = false;
            }
        }

        if (currentTime < nextTimeExecution) return;

        // cycle with timings
        int catchupCount = 0;
        long NANO_ONE_SECOND = 1_000_000_000L;
        long MAX_CYCLE_PER_SECOND = 2_000_000L;
        do {
            catchupCount++;
            int cycle = cpu.step();

            // get next exec
            // nextTimeExecution = (NANO_ONE_SECOND - (getRelativeTimeEpoch() - cpuLastTime)) * cpu.currentCycle / (MAX_CYCLE_PER_SECOND - cyclePerSecond);
            nextTimeExecution += getRelativeTimeEpoch();
            cyclePerSecond += cycle;
            systemGuestCycleTotal += cycle;
        } while (cyclePerSecond < MAX_CYCLE_PER_SECOND && cpuElapsedTime > NANO_ONE_SECOND);

        if (catchupCount > 1) platform.log(null, "CPU catch-up " + catchupCount + " times.");

        // cycle reset
        if (cyclePerSecond >= MAX_CYCLE_PER_SECOND) {
            cyclePerSecond -= MAX_CYCLE_PER_SECOND;
            cpuLastTime = currentTime;
        }

        if (systemCurrentTime > systemLastTime + 1_000_000_000L) {
            systemLastTime = systemCurrentTime;
            platform.log(null, "Cycle accumulated: " + systemGuestCycleTotal);
            systemGuestCycleTotal = 0;
        }
    }

    public void cycleWithoutTiming() {
        int op = mmu.readMemory(cpu.getPC());
        switch (op) {
            case 0xd3:
                testSuiteOut();
                platform.writeLog("\n\n\nTEST ENDED: " + LocalDateTime.now().toString());
                break;
            case 0xdb:
                testSuiteIn();
                break;
        }
        cpu.step();
    }

    private void testSuiteIn() {
        int operation = cpu.getRegC();

        if (operation == 2) {
            char data = (char) cpu.getRegE();
            String dataString = Character.valueOf(data).toString();
            platform.writeLog(dataString);

            if (cpu.getRegE() == 10) {
                platform.writeLog("\n");
            }
        } else if (operation == 9) {
            int address = (cpu.getRegD() << 8) | cpu.getRegE();
            char data;

            while ((data = (char) mmu.readMemory(address)) != '$') {
                String dataString = Character.valueOf(data).toString();
                platform.writeLog(dataString);

                if (cpu.getRegE() == 10) {
                    platform.writeLog("\n");
                }
                address++;
            }
        }

        cpu.setRegA((short) 0xff);
    }

    private void testSuiteOut() {
        isRunning = false;
    }

    public void setRunningState(boolean isRunning) {
        this.isRunning = isRunning;
    }

    public boolean isRunning() {
        return this.isRunning;
    }

    public void setPause(boolean isPaused) {
        this.isPaused = isPaused;
    }

    public boolean isPaused() {
        return this.isPaused;
    }

    private String getLogCurrentPc() {
        return Disassembler.disassemble(mmu, cpu.getPC(), mmu.readMemory(cpu.getPC()));
    }

    public void setPortXor(int index, byte key) {
        keyPort[index] |= key;
    }

    public void setPortAnd(int index, byte key) {
        keyPort[index] &= key;
    }

    private long getRelativeTimeEpoch() {
        return System.nanoTime() - programTimeEpoch;
    }

    public Cpu getCpu() { return cpu; }
}
