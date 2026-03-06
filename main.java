/*
 * J33 — Hybrid claw controller and AI decision engine for precision grip and servo sequences.
 * Combines iron-claw strength tiers with AI-driven target selection and calibration. Single-file build.
 */

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ─── J33 Config (final constants; no mutable globals) ─────────────────────────

final class J33Config {
    static final int J33_MAX_CLAW_STRENGTH = 12;
    static final int J33_MIN_GRIP_PERCENT = 0;
    static final int J33_MAX_GRIP_PERCENT = 100;
    static final int J33_SERVO_AXES = 4;
    static final int J33_MAX_TARGETS_PER_SESSION = 256;
    static final int J33_MAX_PAYLOAD_BYTES = 4096;
    static final int J33_CALIBRATION_SAMPLES = 32;
    static final int J33_AI_DECISION_POOL = 64;
    static final long J33_EPOCH_MS = 86_400_000L;
    static final int J33_VERSION = 3;
    static final long J33_DOMAIN_SALT = 0x9a3e7c1f5b8d0e2a4L;
    static final int J33_DEFAULT_STRENGTH = 6;
    static final int J33_DEFAULT_GRIP = 50;
    static final int J33_SERVO_MIN = 0;
    static final int J33_SERVO_MAX = 4095;
    static final int J33_NAME_LEN = 32;
    static final String J33_BUILD = "j33-claw-3.0";
    static final String J33_CLAW_OPERATOR = "0xBe3f4c8a1D6e9F2b5C0d3E6a8B1c4D7e0F9A2b5";
    static final String J33_IRON_ANCHOR = "0xC4e5F6a7B8c9D0e1F2a3B4c5D6e7F8a9B0c1D2";
    static final String J33_AI_ORACLE = "0xD5f6A7b8C9d0E1f2A3b4C5d6E7f8A9b0C1d2E3";
    static final String J33_CALIBRATOR = "0xE6A7b8C9d0E1f2A3b4C5d6E7f8A9b0C1d2E3f4";
    static final String J33_TREASURY = "0xF7b8C9d0E1f2A3b4C5d6E7f8A9b0C1d2E3f4A5";
    static final String J33_RELAY = "0xA8c9D0e1F2a3B4c5D6e7F8a9B0c1D2e3F4a5B6";
    static final String J33_SENTINEL = "0xB9d0E1f2A3b4C5d6E7f8A9b0C1d2E3f4A5b6C7";
    static final String J33_ZERO = "0x0000000000000000000000000000000000000000";

    private J33Config() {}
}

// ─── J33 Exceptions (unique names) ───────────────────────────────────────────

final class J33NotOperatorException extends RuntimeException {
    J33NotOperatorException() { super("J33: operator only"); }
}
final class J33NotIronAnchorException extends RuntimeException {
    J33NotIronAnchorException() { super("J33: iron anchor only"); }
}
final class J33NotAiOracleException extends RuntimeException {
    J33NotAiOracleException() { super("J33: AI oracle only"); }
}
final class J33NotCalibratorException extends RuntimeException {
    J33NotCalibratorException() { super("J33: calibrator only"); }
}
final class J33ClawNotCalibratedException extends RuntimeException {
    J33ClawNotCalibratedException() { super("J33: claw not calibrated"); }
}
final class J33InvalidStrengthException extends RuntimeException {
    J33InvalidStrengthException() { super("J33: invalid strength tier"); }
}
final class J33InvalidGripException extends RuntimeException {
    J33InvalidGripException() { super("J33: invalid grip"); }
}
final class J33TargetNotFoundException extends RuntimeException {
    J33TargetNotFoundException() { super("J33: target not found"); }
}
final class J33TargetCapReachedException extends RuntimeException {
    J33TargetCapReachedException() { super("J33: target cap reached"); }
}
final class J33PayloadTooLargeException extends RuntimeException {
    J33PayloadTooLargeException() { super("J33: payload too large"); }
}
final class J33ServoAxisOutOfRangeException extends RuntimeException {
    J33ServoAxisOutOfRangeException() { super("J33: servo axis out of range"); }
}
final class J33PausedException extends RuntimeException {
    J33PausedException() { super("J33: paused"); }
}
final class J33ZeroAddressException extends RuntimeException {
    J33ZeroAddressException() { super("J33: zero address"); }
}
final class J33ReentrantException extends RuntimeException {
    J33ReentrantException() { super("J33: reentrant"); }
}
final class J33InvalidSessionException extends RuntimeException {
    J33InvalidSessionException() { super("J33: invalid session"); }
}
final class J33AiDecisionPoolFullException extends RuntimeException {
    J33AiDecisionPoolFullException() { super("J33: AI decision pool full"); }
}
final class J33CalibrationFailedException extends RuntimeException {
    J33CalibrationFailedException() { super("J33: calibration failed"); }
}

// ─── J33 Event payloads ──────────────────────────────────────────────────────

final class J33GripEngagedEvent {
    final long sessionId;
    final int strengthTier;
    final int gripPercent;
    final String operatorHex;
    final long atMs;

    J33GripEngagedEvent(long sessionId, int strengthTier, int gripPercent, String operatorHex, long atMs) {
        this.sessionId = sessionId;
        this.strengthTier = strengthTier;
        this.gripPercent = gripPercent;
        this.operatorHex = operatorHex != null ? operatorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33ClawCalibratedEvent {
    final long sessionId;
    final int[] servoOffsets;
    final String calibratorHex;
    final long atMs;

    J33ClawCalibratedEvent(long sessionId, int[] servoOffsets, String calibratorHex, long atMs) {
        this.sessionId = sessionId;
        this.servoOffsets = servoOffsets != null ? servoOffsets.clone() : new int[0];
        this.calibratorHex = calibratorHex != null ? calibratorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33TargetAcquiredEvent {
    final long targetId;
    final long sessionId;
    final double x;
    final double y;
    final double z;
    final String operatorHex;
    final long atMs;

    J33TargetAcquiredEvent(long targetId, long sessionId, double x, double y, double z, String operatorHex, long atMs) {
        this.targetId = targetId;
        this.sessionId = sessionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.operatorHex = operatorHex != null ? operatorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33AiDecisionEvent {
    final long decisionId;
    final int actionCode;
    final long targetId;
    final String oracleHex;
    final long atMs;

    J33AiDecisionEvent(long decisionId, int actionCode, long targetId, String oracleHex, long atMs) {
        this.decisionId = decisionId;
        this.actionCode = actionCode;
        this.targetId = targetId;
        this.oracleHex = oracleHex != null ? oracleHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33PayloadAttachedEvent {
    final long sessionId;
    final byte[] payloadHash;
    final int sizeBytes;
    final String operatorHex;
    final long atMs;

    J33PayloadAttachedEvent(long sessionId, byte[] payloadHash, int sizeBytes, String operatorHex, long atMs) {
        this.sessionId = sessionId;
        this.payloadHash = payloadHash != null ? payloadHash.clone() : new byte[0];
        this.sizeBytes = sizeBytes;
        this.operatorHex = operatorHex != null ? operatorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33IronClawActivatedEvent {
    final long sessionId;
    final int strengthTier;
    final String anchorHex;
    final long atMs;

    J33IronClawActivatedEvent(long sessionId, int strengthTier, String anchorHex, long atMs) {
        this.sessionId = sessionId;
        this.strengthTier = strengthTier;
        this.anchorHex = anchorHex != null ? anchorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33SessionOpenedEvent {
    final long sessionId;
    final String operatorHex;
    final long atMs;

    J33SessionOpenedEvent(long sessionId, String operatorHex, long atMs) {
        this.sessionId = sessionId;
        this.operatorHex = operatorHex != null ? operatorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33SessionClosedEvent {
    final long sessionId;
    final long atMs;

    J33SessionClosedEvent(long sessionId, long atMs) {
        this.sessionId = sessionId;
        this.atMs = atMs;
    }
}

final class J33PauseToggledEvent {
    final boolean paused;
    final String byHex;
    final long atMs;

    J33PauseToggledEvent(boolean paused, String byHex, long atMs) {
        this.paused = paused;
        this.byHex = byHex != null ? byHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }
}

final class J33ServoMovedEvent {
    final long sessionId;
    final int axisIndex;
    final int fromPosition;
    final int toPosition;
    final long atMs;

    J33ServoMovedEvent(long sessionId, int axisIndex, int fromPosition, int toPosition, long atMs) {
        this.sessionId = sessionId;
        this.axisIndex = axisIndex;
        this.fromPosition = fromPosition;
        this.toPosition = toPosition;
        this.atMs = atMs;
    }
}

final class J33RelayForwardEvent {
    final String relayHex;
    final byte[] payloadHash;
    final long atMs;

    J33RelayForwardEvent(String relayHex, byte[] payloadHash, long atMs) {
        this.relayHex = relayHex != null ? relayHex : J33Config.J33_ZERO;
        this.payloadHash = payloadHash != null ? payloadHash.clone() : new byte[0];
        this.atMs = atMs;
    }
}

final class J33TreasuryCreditEvent {
    final String toHex;
    final BigInteger amountWei;
    final long atMs;

    J33TreasuryCreditEvent(String toHex, BigInteger amountWei, long atMs) {
        this.toHex = toHex != null ? toHex : J33Config.J33_ZERO;
        this.amountWei = amountWei != null ? amountWei : BigInteger.ZERO;
        this.atMs = atMs;
    }
}

// ─── J33 Enums ──────────────────────────────────────────────────────────────

enum J33ClawMode {
    IDLE(0),
    CALIBRATING(1),
    TRACKING(2),
    GRIPPING(3),
    IRON_LOCK(4),
    RELEASING(5),
    ERROR(6);

    private final int code;
    J33ClawMode(int code) { this.code = code; }
    public int getCode() { return code; }
    public static J33ClawMode fromCode(int c) {
        for (J33ClawMode m : values()) if (m.code == c) return m;
        return IDLE;
    }
}

enum J33GripLevel {
    NONE(0),
    LIGHT(25),
    MEDIUM(50),
    FIRM(75),
    FULL(100);

    private final int percent;
    J33GripLevel(int percent) { this.percent = percent; }
    public int getPercent() { return percent; }
    public static J33GripLevel fromPercent(int p) {
        if (p <= 0) return NONE;
        if (p <= 25) return LIGHT;
        if (p <= 50) return MEDIUM;
        if (p <= 75) return FIRM;
        return FULL;
    }
}

enum J33ServoAxis {
    X(0),
    Y(1),
    Z(2),
    ROTATE(3);

    private final int index;
    J33ServoAxis(int index) { this.index = index; }
    public int getIndex() { return index; }
    public static J33ServoAxis fromIndex(int i) {
        for (J33ServoAxis a : values()) if (a.index == i) return a;
        return X;
    }
}

enum J33AIAction {
    HOLD(0),
    MOVE_TO_TARGET(1),
    GRIP(2),
    RELEASE(3),
    CALIBRATE(4),
    IRON_LOCK(5),
    ABORT(6);

    private final int code;
    J33AIAction(int code) { this.code = code; }
    public int getCode() { return code; }
    public static J33AIAction fromCode(int c) {
        for (J33AIAction a : values()) if (a.code == c) return a;
        return HOLD;
    }
}

// ─── J33 State DTOs ─────────────────────────────────────────────────────────

final class J33ClawState {
    private final long sessionId;
    private final J33ClawMode mode;
    private final int strengthTier;
    private final int gripPercent;
    private final int[] servoPositions;
    private final boolean calibrated;
    private final long updatedAtMs;

    J33ClawState(long sessionId, J33ClawMode mode, int strengthTier, int gripPercent, int[] servoPositions, boolean calibrated, long updatedAtMs) {
        this.sessionId = sessionId;
        this.mode = mode != null ? mode : J33ClawMode.IDLE;
        this.strengthTier = Math.max(0, Math.min(J33Config.J33_MAX_CLAW_STRENGTH, strengthTier));
        this.gripPercent = Math.max(J33Config.J33_MIN_GRIP_PERCENT, Math.min(J33Config.J33_MAX_GRIP_PERCENT, gripPercent));
        this.servoPositions = servoPositions != null && servoPositions.length >= J33Config.J33_SERVO_AXES
            ? Arrays.copyOf(servoPositions, J33Config.J33_SERVO_AXES)
            : new int[J33Config.J33_SERVO_AXES];
        this.calibrated = calibrated;
        this.updatedAtMs = updatedAtMs;
    }

    public long getSessionId() { return sessionId; }
    public J33ClawMode getMode() { return mode; }
    public int getStrengthTier() { return strengthTier; }
    public int getGripPercent() { return gripPercent; }
    public int[] getServoPositions() { return servoPositions.clone(); }
    public boolean isCalibrated() { return calibrated; }
    public long getUpdatedAtMs() { return updatedAtMs; }
}

final class J33ServoCommand {
    private final J33ServoAxis axis;
    private final int position;
    private final int speedPercent;

    J33ServoCommand(J33ServoAxis axis, int position, int speedPercent) {
        this.axis = axis != null ? axis : J33ServoAxis.X;
        this.position = position;
        this.speedPercent = Math.max(0, Math.min(100, speedPercent));
    }

    public J33ServoAxis getAxis() { return axis; }
    public int getPosition() { return position; }
    public int getSpeedPercent() { return speedPercent; }
}

final class J33Target {
    private final long targetId;
    private final long sessionId;
    private final double x;
    private final double y;
    private final double z;
    private final long createdAtMs;

    J33Target(long targetId, long sessionId, double x, double y, double z, long createdAtMs) {
        this.targetId = targetId;
        this.sessionId = sessionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.createdAtMs = createdAtMs;
    }

    public long getTargetId() { return targetId; }
    public long getSessionId() { return sessionId; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public long getCreatedAtMs() { return createdAtMs; }
}

final class J33Payload {
    private final long sessionId;
    private final byte[] data;
    private final byte[] hash;
    private final long attachedAtMs;

    J33Payload(long sessionId, byte[] data, byte[] hash, long attachedAtMs) {
        this.sessionId = sessionId;
        this.data = data != null ? data.clone() : new byte[0];
        this.hash = hash != null ? hash.clone() : new byte[0];
        this.attachedAtMs = attachedAtMs;
    }

    public long getSessionId() { return sessionId; }
    public byte[] getData() { return data.clone(); }
    public byte[] getHash() { return hash.clone(); }
    public long getAttachedAtMs() { return attachedAtMs; }
}

final class J33CalibrationRecord {
    private final long sessionId;
    private final int[] offsets;
    private final String calibratorHex;
    private final long atMs;

    J33CalibrationRecord(long sessionId, int[] offsets, String calibratorHex, long atMs) {
        this.sessionId = sessionId;
        this.offsets = offsets != null ? offsets.clone() : new int[J33Config.J33_SERVO_AXES];
        this.calibratorHex = calibratorHex != null ? calibratorHex : J33Config.J33_ZERO;
        this.atMs = atMs;
    }

    public long getSessionId() { return sessionId; }
    public int[] getOffsets() { return offsets.clone(); }
    public String getCalibratorHex() { return calibratorHex; }
    public long getAtMs() { return atMs; }
}

// ─── J33 Claw Engine ────────────────────────────────────────────────────────

public final class J33 {
    private final Map<Long, J33ClawState> sessionStates = new ConcurrentHashMap<>();
    private final Map<Long, J33Target> targets = new ConcurrentHashMap<>();
    private final Map<Long, J33Payload> payloadsBySession = new ConcurrentHashMap<>();
    private final Map<Long, J33CalibrationRecord> calibrations = new ConcurrentHashMap<>();
    private final List<Object> eventLog = new CopyOnWriteArrayList<>();
    private final Queue<J33AiDecisionEvent> aiDecisionPool = new LinkedList<>();
    private final AtomicLong sessionIdGen = new AtomicLong(1);
    private final AtomicLong targetIdGen = new AtomicLong(1);
    private final AtomicLong decisionIdGen = new AtomicLong(1);
    private volatile boolean paused;
    private volatile int guard;

    private final String operatorHex;
    private final String ironAnchorHex;
    private final String aiOracleHex;
    private final String calibratorHex;

    public J33() {
        this.operatorHex = J33Config.J33_CLAW_OPERATOR;
        this.ironAnchorHex = J33Config.J33_IRON_ANCHOR;
        this.aiOracleHex = J33Config.J33_AI_ORACLE;
        this.calibratorHex = J33Config.J33_CALIBRATOR;
    }

    public J33(String operatorHex, String ironAnchorHex, String aiOracleHex, String calibratorHex) {
        this.operatorHex = operatorHex != null ? operatorHex : J33Config.J33_CLAW_OPERATOR;
        this.ironAnchorHex = ironAnchorHex != null ? ironAnchorHex : J33Config.J33_IRON_ANCHOR;
        this.aiOracleHex = aiOracleHex != null ? aiOracleHex : J33Config.J33_AI_ORACLE;
        this.calibratorHex = calibratorHex != null ? calibratorHex : J33Config.J33_CALIBRATOR;
    }

    public String getOperatorHex() { return operatorHex; }
    public String getIronAnchorHex() { return ironAnchorHex; }
    public String getAiOracleHex() { return aiOracleHex; }
    public String getCalibratorHex() { return calibratorHex; }
    public boolean isPaused() { return paused; }

    private void requireOperator(String caller) {
        if (caller == null || !caller.equalsIgnoreCase(operatorHex)) throw new J33NotOperatorException();
    }
    private void requireIronAnchor(String caller) {
        if (caller == null || !caller.equalsIgnoreCase(ironAnchorHex)) throw new J33NotIronAnchorException();
    }
    private void requireAiOracle(String caller) {
        if (caller == null || !caller.equalsIgnoreCase(aiOracleHex)) throw new J33NotAiOracleException();
    }
    private void requireCalibrator(String caller) {
        if (caller == null || !caller.equalsIgnoreCase(calibratorHex)) throw new J33NotCalibratorException();
    }
    private void requireNotPaused() {
        if (paused) throw new J33PausedException();
    }
    private void requireNotReentrant() {
        if (guard != 0) throw new J33ReentrantException();
        guard = 1;
    }
    private void releaseGuard() { guard = 0; }

    public long openSession(String caller) {
        requireOperator(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            long sid = sessionIdGen.getAndIncrement();
            J33ClawState state = new J33ClawState(sid, J33ClawMode.IDLE, 0, 0, new int[J33Config.J33_SERVO_AXES], false, System.currentTimeMillis());
            sessionStates.put(sid, state);
            eventLog.add(new J33SessionOpenedEvent(sid, caller, System.currentTimeMillis()));
            return sid;
        } finally { releaseGuard(); }
    }

    public void closeSession(long sessionId, String caller) {
        requireOperator(caller);
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            sessionStates.remove(sessionId);
            payloadsBySession.remove(sessionId);
            eventLog.add(new J33SessionClosedEvent(sessionId, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public void calibrate(long sessionId, int[] offsets, String caller) {
        requireCalibrator(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            J33ClawState state = sessionStates.get(sessionId);
            if (state == null) throw new J33InvalidSessionException();
            if (offsets == null || offsets.length < J33Config.J33_SERVO_AXES) throw new J33CalibrationFailedException();
            int[] off = Arrays.copyOf(offsets, J33Config.J33_SERVO_AXES);
            J33CalibrationRecord rec = new J33CalibrationRecord(sessionId, off, caller, System.currentTimeMillis());
            calibrations.put(sessionId, rec);
            J33ClawState next = new J33ClawState(sessionId, J33ClawMode.IDLE, state.getStrengthTier(), state.getGripPercent(), state.getServoPositions(), true, System.currentTimeMillis());
            sessionStates.put(sessionId, next);
            eventLog.add(new J33ClawCalibratedEvent(sessionId, off, caller, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public long acquireTarget(long sessionId, double x, double y, double z, String caller) {
        requireOperator(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            long count = targets.values().stream().filter(t -> t.getSessionId() == sessionId).count();
            if (count >= J33Config.J33_MAX_TARGETS_PER_SESSION) throw new J33TargetCapReachedException();
            long tid = targetIdGen.getAndIncrement();
            J33Target t = new J33Target(tid, sessionId, x, y, z, System.currentTimeMillis());
            targets.put(tid, t);
            eventLog.add(new J33TargetAcquiredEvent(tid, sessionId, x, y, z, caller, System.currentTimeMillis()));
            return tid;
        } finally { releaseGuard(); }
    }

    public void engageGrip(long sessionId, int strengthTier, int gripPercent, String caller) {
        requireOperator(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            if (strengthTier < 0 || strengthTier > J33Config.J33_MAX_CLAW_STRENGTH) throw new J33InvalidStrengthException();
            if (gripPercent < J33Config.J33_MIN_GRIP_PERCENT || gripPercent > J33Config.J33_MAX_GRIP_PERCENT) throw new J33InvalidGripException();
            J33ClawState state = sessionStates.get(sessionId);
            J33ClawState next = new J33ClawState(sessionId, J33ClawMode.GRIPPING, strengthTier, gripPercent, state.getServoPositions(), state.isCalibrated(), System.currentTimeMillis());
            sessionStates.put(sessionId, next);
            eventLog.add(new J33GripEngagedEvent(sessionId, strengthTier, gripPercent, caller, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public void activateIronClaw(long sessionId, int strengthTier, String caller) {
        requireIronAnchor(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            if (strengthTier < 0 || strengthTier > J33Config.J33_MAX_CLAW_STRENGTH) throw new J33InvalidStrengthException();
            J33ClawState state = sessionStates.get(sessionId);
            if (!state.isCalibrated()) throw new J33ClawNotCalibratedException();
            J33ClawState next = new J33ClawState(sessionId, J33ClawMode.IRON_LOCK, strengthTier, state.getGripPercent(), state.getServoPositions(), true, System.currentTimeMillis());
            sessionStates.put(sessionId, next);
            eventLog.add(new J33IronClawActivatedEvent(sessionId, strengthTier, caller, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public void attachPayload(long sessionId, byte[] data, String caller) {
        requireOperator(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            if (data != null && data.length > J33Config.J33_MAX_PAYLOAD_BYTES) throw new J33PayloadTooLargeException();
            byte[] payload = data != null ? data : new byte[0];
            byte[] hash = sha256(payload);
            J33Payload p = new J33Payload(sessionId, payload, hash, System.currentTimeMillis());
            payloadsBySession.put(sessionId, p);
            eventLog.add(new J33PayloadAttachedEvent(sessionId, hash, payload.length, caller, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public void pushAiDecision(int actionCode, long targetId, String caller) {
        requireAiOracle(caller);
        requireNotPaused();
        requireNotReentrant();
        try {
            if (aiDecisionPool.size() >= J33Config.J33_AI_DECISION_POOL) throw new J33AiDecisionPoolFullException();
            long did = decisionIdGen.getAndIncrement();
            J33AiDecisionEvent ev = new J33AiDecisionEvent(did, actionCode, targetId, caller, System.currentTimeMillis());
            synchronized (aiDecisionPool) { aiDecisionPool.add(ev); }
            eventLog.add(ev);
        } finally { releaseGuard(); }
    }

    public void setPaused(boolean paused, String caller) {
        requireOperator(caller);
        requireNotReentrant();
        try {
            this.paused = paused;
            eventLog.add(new J33PauseToggledEvent(paused, caller, System.currentTimeMillis()));
        } finally { releaseGuard(); }
    }

    public J33ClawState getClawState(long sessionId) {
        return sessionStates.get(sessionId);
    }

    public J33Target getTarget(long targetId) {
        return targets.get(targetId);
    }

    public J33Payload getPayload(long sessionId) {
        return payloadsBySession.get(sessionId);
    }

    public J33CalibrationRecord getCalibration(long sessionId) {
        return calibrations.get(sessionId);
    }

    public List<J33Target> getTargetsForSession(long sessionId) {
        return targets.values().stream().filter(t -> t.getSessionId() == sessionId).collect(Collectors.toList());
    }

    public List<Object> getEventLog() {
        return new ArrayList<>(eventLog);
    }

    public List<J33AiDecisionEvent> getAiDecisionPool() {
        synchronized (aiDecisionPool) { return new ArrayList<>(aiDecisionPool); }
    }

    public void applyServoCommand(long sessionId, J33ServoCommand cmd, String caller) {
        requireOperator(caller);
        requireNotPaused();
        if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
        J33ClawState state = sessionStates.get(sessionId);
        if (!state.isCalibrated()) throw new J33ClawNotCalibratedException();
        int idx = cmd.getAxis().getIndex();
        if (idx < 0 || idx >= J33Config.J33_SERVO_AXES) throw new J33ServoAxisOutOfRangeException();
        int[] pos = state.getServoPositions();
        pos[idx] = cmd.getPosition();
        J33ClawState next = new J33ClawState(sessionId, state.getMode(), state.getStrengthTier(), state.getGripPercent(), pos, true, System.currentTimeMillis());
        sessionStates.put(sessionId, next);
    }

    public void releaseGrip(long sessionId, String caller) {
        requireOperator(caller);
        requireNotReentrant();
        try {
            if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
            J33ClawState state = sessionStates.get(sessionId);
            J33ClawState next = new J33ClawState(sessionId, J33ClawMode.RELEASING, 0, 0, state.getServoPositions(), state.isCalibrated(), System.currentTimeMillis());
            sessionStates.put(sessionId, next);
        } finally { releaseGuard(); }
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input != null ? input : new byte[0]);
        } catch (NoSuchAlgorithmException e) {
            throw new J33CalibrationFailedException();
        }
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "0x";
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static BigInteger weiFromEther(String etherStr) {
        if (etherStr == null || etherStr.trim().isEmpty()) return BigInteger.ZERO;
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(etherStr.trim());
            return d.multiply(java.math.BigDecimal.valueOf(1_000_000_000_000_000_000L)).toBigInteger();
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    public Set<Long> getActiveSessionIds() {
        return new HashSet<>(sessionStates.keySet());
    }

    public int getTargetCount() { return targets.size(); }
    public int getCalibrationCount() { return calibrations.size(); }
    public int getEventLogSize() { return eventLog.size(); }

    public boolean isSessionActive(long sessionId) {
        return sessionStates.containsKey(sessionId);
    }

    public boolean isTargetValid(long targetId) {
        return targets.containsKey(targetId);
    }

    public J33ClawMode getSessionMode(long sessionId) {
        J33ClawState s = sessionStates.get(sessionId);
        return s != null ? s.getMode() : null;
    }

    public void batchAcquireTargets(long sessionId, double[][] coordinates, String caller) {
        requireOperator(caller);
        requireNotPaused();
        if (coordinates == null) return;
        for (double[] c : coordinates) {
            if (c != null && c.length >= 3)
                acquireTarget(sessionId, c[0], c[1], c[2], caller);
        }
    }

    public J33AiDecisionEvent pollAiDecision() {
        synchronized (aiDecisionPool) {
            return aiDecisionPool.isEmpty() ? null : aiDecisionPool.poll();
        }
    }

    public void clearAiDecisionPool(String caller) {
        requireAiOracle(caller);
        synchronized (aiDecisionPool) { aiDecisionPool.clear(); }
    }

    public static boolean isValidAddressHex(String hex) {
        if (hex == null) return false;
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (h.length() != 40) return false;
        return h.matches("[0-9a-fA-F]+");
    }

    public static String normalizeAddress(String hex) {
        if (hex == null || hex.trim().isEmpty()) return J33Config.J33_ZERO;
        String h = hex.trim().startsWith("0x") ? hex.trim().substring(2) : hex.trim();
        if (h.length() > 40) h = h.substring(0, 40);
        return "0x" + h.toLowerCase();
    }

    public J33SessionSummary getSessionSummary(long sessionId) {
        J33ClawState state = sessionStates.get(sessionId);
        if (state == null) return null;
        List<J33Target> sessionTargets = getTargetsForSession(sessionId);
        J33Payload payload = payloadsBySession.get(sessionId);
        J33CalibrationRecord cal = calibrations.get(sessionId);
        return new J33SessionSummary(sessionId, state, sessionTargets, payload, cal);
    }

    public List<J33SessionSummary> getAllSessionSummaries() {
        List<J33SessionSummary> out = new ArrayList<>();
        for (Long sid : sessionStates.keySet()) {
            J33SessionSummary s = getSessionSummary(sid);
            if (s != null) out.add(s);
        }
        return out;
    }

    public static final class J33SessionSummary {
        private final long sessionId;
        private final J33ClawState state;
        private final List<J33Target> targets;
        private final J33Payload payload;
        private final J33CalibrationRecord calibration;

        J33SessionSummary(long sessionId, J33ClawState state, List<J33Target> targets, J33Payload payload, J33CalibrationRecord calibration) {
            this.sessionId = sessionId;
            this.state = state;
            this.targets = targets != null ? new ArrayList<>(targets) : Collections.emptyList();
            this.payload = payload;
            this.calibration = calibration;
        }

        public long getSessionId() { return sessionId; }
        public J33ClawState getState() { return state; }
        public List<J33Target> getTargets() { return targets; }
        public J33Payload getPayload() { return payload; }
        public J33CalibrationRecord getCalibration() { return calibration; }
    }

    public J33EngineSnapshot snapshot() {
        Map<Long, J33ClawState> statesCopy = new HashMap<>(sessionStates);
        Map<Long, J33Target> targetsCopy = new HashMap<>(targets);
        Map<Long, J33Payload> payloadsCopy = new HashMap<>(payloadsBySession);
        Map<Long, J33CalibrationRecord> calCopy = new HashMap<>(calibrations);
        List<Object> logCopy = new ArrayList<>(eventLog);
        List<J33AiDecisionEvent> poolCopy = getAiDecisionPool();
        return new J33EngineSnapshot(statesCopy, targetsCopy, payloadsCopy, calCopy, logCopy, poolCopy, paused, sessionIdGen.get(), targetIdGen.get(), decisionIdGen.get());
    }

    public static final class J33EngineSnapshot {
        private final Map<Long, J33ClawState> sessionStates;
        private final Map<Long, J33Target> targets;
        private final Map<Long, J33Payload> payloads;
        private final Map<Long, J33CalibrationRecord> calibrations;
        private final List<Object> eventLog;
        private final List<J33AiDecisionEvent> aiPool;
        private final boolean paused;
        private final long nextSessionId;
        private final long nextTargetId;
        private final long nextDecisionId;

        J33EngineSnapshot(Map<Long, J33ClawState> sessionStates, Map<Long, J33Target> targets, Map<Long, J33Payload> payloads, Map<Long, J33CalibrationRecord> calibrations, List<Object> eventLog, List<J33AiDecisionEvent> aiPool, boolean paused, long nextSessionId, long nextTargetId, long nextDecisionId) {
            this.sessionStates = Collections.unmodifiableMap(new HashMap<>(sessionStates));
            this.targets = Collections.unmodifiableMap(new HashMap<>(targets));
            this.payloads = Collections.unmodifiableMap(new HashMap<>(payloads));
            this.calibrations = Collections.unmodifiableMap(new HashMap<>(calibrations));
            this.eventLog = Collections.unmodifiableList(new ArrayList<>(eventLog));
            this.aiPool = Collections.unmodifiableList(new ArrayList<>(aiPool));
            this.paused = paused;
            this.nextSessionId = nextSessionId;
            this.nextTargetId = nextTargetId;
            this.nextDecisionId = nextDecisionId;
        }

        public Map<Long, J33ClawState> getSessionStates() { return sessionStates; }
        public Map<Long, J33Target> getTargets() { return targets; }
        public Map<Long, J33Payload> getPayloads() { return payloads; }
        public Map<Long, J33CalibrationRecord> getCalibrations() { return calibrations; }
        public List<Object> getEventLog() { return eventLog; }
        public List<J33AiDecisionEvent> getAiPool() { return aiPool; }
        public boolean isPaused() { return paused; }
        public long getNextSessionId() { return nextSessionId; }
        public long getNextTargetId() { return nextTargetId; }
        public long getNextDecisionId() { return nextDecisionId; }
    }

    public static final class J33ContractAbi {
        public static final String OPEN_SESSION = "openSession(address)";
        public static final String CLOSE_SESSION = "closeSession(uint64,address)";
        public static final String CALIBRATE = "calibrate(uint64,int32[4],address)";
        public static final String ACQUIRE_TARGET = "acquireTarget(uint64,uint256,uint256,uint256,address)";
        public static final String ENGAGE_GRIP = "engageGrip(uint64,uint8,uint8,address)";
        public static final String ACTIVATE_IRON_CLAW = "activateIronClaw(uint64,uint8,address)";
        public static final String ATTACH_PAYLOAD = "attachPayload(uint64,bytes,address)";
        public static final String PUSH_AI_DECISION = "pushAiDecision(uint8,uint64,address)";
        public static final String SET_PAUSED = "setPaused(bool,address)";
        public static final String RELEASE_GRIP = "releaseGrip(uint64,address)";
        public static final String EVENT_GRIP_ENGAGED = "GripEngaged(uint64,uint8,uint8,address,uint256)";
        public static final String EVENT_CLAW_CALIBRATED = "ClawCalibrated(uint64,int32[4],address,uint256)";
        public static final String EVENT_TARGET_ACQUIRED = "TargetAcquired(uint64,uint64,uint256,uint256,uint256,address,uint256)";
        public static final String EVENT_AI_DECISION = "AiDecision(uint64,uint8,uint64,address,uint256)";
        public static final String EVENT_IRON_CLAW_ACTIVATED = "IronClawActivated(uint64,uint8,address,uint256)";
        public static final String ERROR_NOT_OPERATOR = "J33_NotOperator()";
        public static final String ERROR_NOT_IRON_ANCHOR = "J33_NotIronAnchor()";
        public static final String ERROR_NOT_AI_ORACLE = "J33_NotAiOracle()";
        public static final String ERROR_CLAW_NOT_CALIBRATED = "J33_ClawNotCalibrated()";
        public static final String ERROR_PAUSED = "J33_Paused()";
        public static final String ERROR_REENTRANT = "J33_Reentrant()";
        public static final String NAMESPACE = "J33.claw.v3";
    }

    public static final class J33AbiMethodNames {
        public static final String OPEN_SESSION = "openSession";
        public static final String CLOSE_SESSION = "closeSession";
        public static final String CALIBRATE = "calibrate";
        public static final String ACQUIRE_TARGET = "acquireTarget";
        public static final String ENGAGE_GRIP = "engageGrip";
        public static final String ACTIVATE_IRON_CLAW = "activateIronClaw";
        public static final String ATTACH_PAYLOAD = "attachPayload";
        public static final String PUSH_AI_DECISION = "pushAiDecision";
        public static final String SET_PAUSED = "setPaused";
        public static final String RELEASE_GRIP = "releaseGrip";
        public static final String GET_CLAW_STATE = "getClawState";
        public static final String GET_TARGET = "getTarget";
        public static final String GET_PAYLOAD = "getPayload";
        public static final String GET_CALIBRATION = "getCalibration";
        public static final String GET_TARGETS_FOR_SESSION = "getTargetsForSession";
        public static final String GET_EVENT_LOG = "getEventLog";
        public static final String GET_AI_DECISION_POOL = "getAiDecisionPool";
        public static final String APPLY_SERVO_COMMAND = "applyServoCommand";
        public static final String RUN_SERVO_SEQUENCE = "runServoSequence";
        public static final String[] ALL_METHODS = {
            OPEN_SESSION, CLOSE_SESSION, CALIBRATE, ACQUIRE_TARGET, ENGAGE_GRIP,
            ACTIVATE_IRON_CLAW, ATTACH_PAYLOAD, PUSH_AI_DECISION, SET_PAUSED, RELEASE_GRIP,
            GET_CLAW_STATE, GET_TARGET, GET_PAYLOAD, GET_CALIBRATION, GET_TARGETS_FOR_SESSION,
            GET_EVENT_LOG, GET_AI_DECISION_POOL, APPLY_SERVO_COMMAND, RUN_SERVO_SEQUENCE
        };
    }

    public static final class J33RoleNames {
        public static final String OPERATOR = "operator";
        public static final String IRON_ANCHOR = "iron_anchor";
        public static final String AI_ORACLE = "ai_oracle";
        public static final String CALIBRATOR = "calibrator";
        public static final String TREASURY = "treasury";
        public static final String RELAY = "relay";
        public static final String SENTINEL = "sentinel";
    }

    public static String getRoleAddress(String role) {
        if (role == null) return J33Config.J33_ZERO;
        switch (role.toLowerCase()) {
            case "operator": return J33Config.J33_CLAW_OPERATOR;
            case "iron_anchor": return J33Config.J33_IRON_ANCHOR;
            case "ai_oracle": return J33Config.J33_AI_ORACLE;
            case "calibrator": return J33Config.J33_CALIBRATOR;
            case "treasury": return J33Config.J33_TREASURY;
            case "relay": return J33Config.J33_RELAY;
            case "sentinel": return J33Config.J33_SENTINEL;
            default: return J33Config.J33_ZERO;
        }
    }

    public static final class J33EventTopics {
        public static final String GRIP_ENGAGED_TOPIC = "0x" + Integer.toHexString(J33EventNames.GRIP_ENGAGED.hashCode());
        public static final String CLAW_CALIBRATED_TOPIC = "0x" + Integer.toHexString(J33EventNames.CLAW_CALIBRATED.hashCode());
        public static final String TARGET_ACQUIRED_TOPIC = "0x" + Integer.toHexString(J33EventNames.TARGET_ACQUIRED.hashCode());
        public static final String AI_DECISION_TOPIC = "0x" + Integer.toHexString(J33EventNames.AI_DECISION.hashCode());
        public static final String IRON_CLAW_ACTIVATED_TOPIC = "0x" + Integer.toHexString(J33EventNames.IRON_CLAW_ACTIVATED.hashCode());
        public static final String SESSION_OPENED_TOPIC = "0x" + Integer.toHexString(J33EventNames.SESSION_OPENED.hashCode());
        public static final String SESSION_CLOSED_TOPIC = "0x" + Integer.toHexString(J33EventNames.SESSION_CLOSED.hashCode());
    }

    public static int clampStrength(int tier) {
        return Math.max(0, Math.min(J33Config.J33_MAX_CLAW_STRENGTH, tier));
    }

    public static int clampGrip(int percent) {
        return Math.max(J33Config.J33_MIN_GRIP_PERCENT, Math.min(J33Config.J33_MAX_GRIP_PERCENT, percent));
    }

    public static int clampServoPosition(int pos) {
        return Math.max(J33Config.J33_SERVO_MIN, Math.min(J33Config.J33_SERVO_MAX, pos));
    }

    public J33GripLevel recommendGripForStrength(int strengthTier) {
        if (strengthTier <= 2) return J33GripLevel.LIGHT;
        if (strengthTier <= 5) return J33GripLevel.MEDIUM;
        if (strengthTier <= 8) return J33GripLevel.FIRM;
        return J33GripLevel.FULL;
    }

    public static final class J33Limits {
        public static int maxClawStrength() { return J33Config.J33_MAX_CLAW_STRENGTH; }
        public static int minGripPercent() { return J33Config.J33_MIN_GRIP_PERCENT; }
        public static int maxGripPercent() { return J33Config.J33_MAX_GRIP_PERCENT; }
        public static int servoAxes() { return J33Config.J33_SERVO_AXES; }
        public static int maxTargetsPerSession() { return J33Config.J33_MAX_TARGETS_PER_SESSION; }
        public static int maxPayloadBytes() { return J33Config.J33_MAX_PAYLOAD_BYTES; }
        public static int calibrationSamples() { return J33Config.J33_CALIBRATION_SAMPLES; }
        public static int aiDecisionPoolSize() { return J33Config.J33_AI_DECISION_POOL; }
        public static long epochMs() { return J33Config.J33_EPOCH_MS; }
    }

    public static final class J33Defaults {
        public static final int STRENGTH = J33Config.J33_DEFAULT_STRENGTH;
        public static final int GRIP = J33Config.J33_DEFAULT_GRIP;
        public static final int[] SERVO_ZERO = new int[]{0, 0, 0, 0};
    }

    public static final class J33Runbook {
        public static final String STEP_1 = "Open session via openSession(operator)";
        public static final String STEP_2 = "Calibrate claw via calibrate(sessionId, offsets[4], calibrator)";
        public static final String STEP_3 = "Acquire target(s) via acquireTarget(sessionId, x, y, z, operator)";
        public static final String STEP_4 = "Engage grip via engageGrip(sessionId, strengthTier, gripPercent, operator)";
        public static final String STEP_5 = "Optionally activate iron claw via activateIronClaw(sessionId, strengthTier, ironAnchor)";
        public static final String STEP_6 = "Release via releaseGrip(sessionId, operator)";
        public static final String STEP_7 = "Close session via closeSession(sessionId, operator)";
        public static final String[] STEPS = new String[]{ STEP_1, STEP_2, STEP_3, STEP_4, STEP_5, STEP_6, STEP_7 };
        public static List<String> getSteps() { return Arrays.asList(STEPS); }
    }

    public static final class J33Doc {
        public static final String DESC_GRIP_ENGAGED = "Emitted when operator engages grip at a given strength and percent";
        public static final String DESC_CLAW_CALIBRATED = "Emitted when calibrator completes calibration for a session";
        public static final String DESC_TARGET_ACQUIRED = "Emitted when a target (x,y,z) is registered for a session";
        public static final String DESC_AI_DECISION = "Emitted when AI oracle pushes a decision (action code, target id)";
        public static final String DESC_IRON_CLAW_ACTIVATED = "Emitted when iron anchor activates iron-claw lock at strength tier";
        public static final String DESC_SESSION_OPENED = "Emitted when a new session is opened by operator";
        public static final String DESC_SESSION_CLOSED = "Emitted when session is closed by operator";
        public static final String DESC_PAUSE_TOGGLED = "Emitted when operator toggles global pause";
        public static final String ERR_NOT_OPERATOR = "Caller is not the claw operator";
        public static final String ERR_NOT_IRON_ANCHOR = "Caller is not the iron anchor";
        public static final String ERR_NOT_AI_ORACLE = "Caller is not the AI oracle";
        public static final String ERR_CLAW_NOT_CALIBRATED = "Claw must be calibrated before this action";
        public static final String ERR_PAUSED = "Engine is paused";
        public static final String ERR_REENTRANT = "Reentrant call detected";
    }

    public static final class J33Selectors {
        public static long openSessionSelector() { return keccak256Selector(J33ContractAbi.OPEN_SESSION); }
        public static long closeSessionSelector() { return keccak256Selector(J33ContractAbi.CLOSE_SESSION); }
        public static long calibrateSelector() { return keccak256Selector(J33ContractAbi.CALIBRATE); }
        public static long acquireTargetSelector() { return keccak256Selector(J33ContractAbi.ACQUIRE_TARGET); }
        public static long engageGripSelector() { return keccak256Selector(J33ContractAbi.ENGAGE_GRIP); }
        public static long activateIronClawSelector() { return keccak256Selector(J33ContractAbi.ACTIVATE_IRON_CLAW); }
        public static long attachPayloadSelector() { return keccak256Selector(J33ContractAbi.ATTACH_PAYLOAD); }
        public static long pushAiDecisionSelector() { return keccak256Selector(J33ContractAbi.PUSH_AI_DECISION); }
        public static long setPausedSelector() { return keccak256Selector(J33ContractAbi.SET_PAUSED); }
        public static long releaseGripSelector() { return keccak256Selector(J33ContractAbi.RELEASE_GRIP); }
    }

    public long getNextSessionId() { return sessionIdGen.get(); }
    public long getNextTargetId() { return targetIdGen.get(); }
    public long getNextDecisionId() { return decisionIdGen.get(); }

    public List<J33CalibrationRecord> getAllCalibrations() {
        return new ArrayList<>(calibrations.values());
    }

    public List<J33Target> getAllTargets() {
        return new ArrayList<>(targets.values());
    }

    public static int servoIndex(J33ServoAxis axis) {
        return axis != null ? axis.getIndex() : 0;
    }

    public static J33ClawMode modeFromCode(int code) {
        return J33ClawMode.fromCode(code);
    }

    public static J33AIAction actionFromCode(int code) {
        return J33AIAction.fromCode(code);
    }

    public static final class J33GasLimits {
        public static int forOpenSession() { return J33GasEstimates.OPEN_SESSION; }
        public static int forCloseSession() { return J33GasEstimates.CLOSE_SESSION; }
        public static int forCalibrate() { return J33GasEstimates.CALIBRATE; }
        public static int forAcquireTarget() { return J33GasEstimates.ACQUIRE_TARGET; }
        public static int forEngageGrip() { return J33GasEstimates.ENGAGE_GRIP; }
        public static int forActivateIronClaw() { return J33GasEstimates.ACTIVATE_IRON_CLAW; }
        public static int forAttachPayload() { return J33GasEstimates.ATTACH_PAYLOAD; }
        public static int forPushAiDecision() { return J33GasEstimates.PUSH_AI_DECISION; }
        public static int forSetPaused() { return J33GasEstimates.SET_PAUSED; }
        public static int forReleaseGrip() { return J33GasEstimates.RELEASE_GRIP; }
    }

    public Optional<J33Target> findTarget(long targetId) {
        return Optional.ofNullable(targets.get(targetId));
    }

    public Optional<J33CalibrationRecord> findCalibration(long sessionId) {
        return Optional.ofNullable(calibrations.get(sessionId));
    }

    public Optional<J33Payload> findPayload(long sessionId) {
        return Optional.ofNullable(payloadsBySession.get(sessionId));
    }

    public static final class J33Status {
        public static final String OK = "ok";
        public static final String PAUSED = "paused";
        public static final String ERROR = "error";
        public static final String NOT_CALIBRATED = "not_calibrated";
    }

    public String getStatus() {
        if (paused) return J33Status.PAUSED;
        return J33Status.OK;
    }

    public static final class J33Flags {
        public static final int FLAG_NONE = 0;
        public static final int FLAG_CALIBRATED = 1;
        public static final int FLAG_IRON_ACTIVE = 2;
        public static final int FLAG_PAUSED = 4;
        public static boolean hasCalibrated(int flags) { return (flags & FLAG_CALIBRATED) != 0; }
        public static boolean hasIronActive(int flags) { return (flags & FLAG_IRON_ACTIVE) != 0; }
        public static boolean hasPaused(int flags) { return (flags & FLAG_PAUSED) != 0; }
    }

    public static String[] allRoleAddresses() {
        return new String[]{
            J33Config.J33_CLAW_OPERATOR,
            J33Config.J33_IRON_ANCHOR,
            J33Config.J33_AI_ORACLE,
            J33Config.J33_CALIBRATOR,
            J33Config.J33_TREASURY,
            J33Config.J33_RELAY,
            J33Config.J33_SENTINEL
        };
    }

    public int getFlags() {
        int f = J33Flags.FLAG_NONE;
        if (paused) f |= J33Flags.FLAG_PAUSED;
        boolean anyCalibrated = calibrations.size() > 0;
        if (anyCalibrated) f |= J33Flags.FLAG_CALIBRATED;
        for (J33ClawState s : sessionStates.values()) {
            if (s.getMode() == J33ClawMode.IRON_LOCK) { f |= J33Flags.FLAG_IRON_ACTIVE; break; }
        }
        return f;
    }

    public static boolean isZeroAddress(String hex) {
        return hex == null || hex.trim().isEmpty() || J33Config.J33_ZERO.equalsIgnoreCase(hex.replace("0x", "").trim()) || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(hex.trim());
    }

    public static String padHex64(String hex) {
        if (hex == null) return "0x" + "0".repeat(64);
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (h.length() >= 64) return "0x" + h.substring(0, 64);
        return "0x" + "0".repeat(64 - h.length()) + h;
    }

    public boolean canEngageGrip(long sessionId) {
        J33ClawState s = sessionStates.get(sessionId);
        return s != null && s.isCalibrated() && !paused;
    }

    public boolean canActivateIronClaw(long sessionId) {
        J33ClawState s = sessionStates.get(sessionId);
        return s != null && s.isCalibrated() && !paused;
    }

    public int getTotalTargetCount() {
        return targets.size();
    }

    public String getConfigJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":").append(J33Config.J33_VERSION);
        sb.append(",\"operator\":\"").append(operatorHex).append("\"");
        sb.append(",\"ironAnchor\":\"").append(ironAnchorHex).append("\"");
        sb.append(",\"aiOracle\":\"").append(aiOracleHex).append("\"");
        sb.append(",\"calibrator\":\"").append(calibratorHex).append("\"");
        sb.append(",\"paused\":").append(paused);
        sb.append(",\"maxStrength\":").append(J33Config.J33_MAX_CLAW_STRENGTH);
        sb.append(",\"maxTargetsPerSession\":").append(J33Config.J33_MAX_TARGETS_PER_SESSION);
        sb.append(",\"maxPayloadBytes\":").append(J33Config.J33_MAX_PAYLOAD_BYTES);
        sb.append(",\"aiDecisionPoolSize\":").append(J33Config.J33_AI_DECISION_POOL);
        sb.append("}");
        return sb.toString();
    }

    public void runServoSequence(long sessionId, List<J33ServoCommand> commands, String caller) {
        requireOperator(caller);
        requireNotPaused();
        if (!sessionStates.containsKey(sessionId)) throw new J33InvalidSessionException();
        J33ClawState state = sessionStates.get(sessionId);
        if (!state.isCalibrated()) throw new J33ClawNotCalibratedException();
        if (commands == null) return;
        int[] pos = state.getServoPositions().clone();
        for (J33ServoCommand cmd : commands) {
            int idx = cmd.getAxis().getIndex();
            if (idx < 0 || idx >= J33Config.J33_SERVO_AXES) throw new J33ServoAxisOutOfRangeException();
            int from = pos[idx];
            pos[idx] = cmd.getPosition();
            eventLog.add(new J33ServoMovedEvent(sessionId, idx, from, pos[idx], System.currentTimeMillis()));
        }
        J33ClawState next = new J33ClawState(sessionId, state.getMode(), state.getStrengthTier(), state.getGripPercent(), pos, true, System.currentTimeMillis());
        sessionStates.put(sessionId, next);
    }

    public List<J33Target> getTargetsInRadius(long sessionId, double cx, double cy, double cz, double radius) {
        List<J33Target> out = new ArrayList<>();
        for (J33Target t : targets.values()) {
            if (t.getSessionId() != sessionId) continue;
            double dx = t.getX() - cx, dy = t.getY() - cy, dz = t.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) out.add(t);
        }
        return out;
    }

    public int getStrengthTierForSession(long sessionId) {
        J33ClawState s = sessionStates.get(sessionId);
        return s != null ? s.getStrengthTier() : 0;
    }

    public J33GripLevel getGripLevelForSession(long sessionId) {
        J33ClawState s = sessionStates.get(sessionId);
        return s != null ? J33GripLevel.fromPercent(s.getGripPercent()) : J33GripLevel.NONE;
    }

    public static final class J33RelayAdapter {
        private final String relayHex;

        public J33RelayAdapter(String relayHex) {
            this.relayHex = relayHex != null ? relayHex : J33Config.J33_RELAY;
        }

        public String getRelayHex() { return relayHex; }

        public boolean forward(byte[] payloadHash) {
            return payloadHash != null && payloadHash.length == 32;
        }
    }

    public static final class J33TreasuryAdapter {
        private final String treasuryHex;
        private final Map<String, BigInteger> balances = new ConcurrentHashMap<>();

        public J33TreasuryAdapter(String treasuryHex) {
            this.treasuryHex = treasuryHex != null ? treasuryHex : J33Config.J33_TREASURY;
        }

        public String getTreasuryHex() { return treasuryHex; }

        public void credit(String toHex, BigInteger amountWei) {
            if (toHex == null || amountWei == null || amountWei.signum() <= 0) return;
            balances.merge(toHex, amountWei, BigInteger::add);
        }

        public BigInteger balanceOf(String hex) {
            return balances.getOrDefault(hex != null ? hex : J33Config.J33_ZERO, BigInteger.ZERO);
        }
    }

    public static final class J33Validation {
        public static boolean isValidStrength(int tier) {
            return tier >= 0 && tier <= J33Config.J33_MAX_CLAW_STRENGTH;
        }
        public static boolean isValidGripPercent(int p) {
            return p >= J33Config.J33_MIN_GRIP_PERCENT && p <= J33Config.J33_MAX_GRIP_PERCENT;
        }
        public static boolean isValidPayloadSize(int bytes) {
            return bytes >= 0 && bytes <= J33Config.J33_MAX_PAYLOAD_BYTES;
        }
        public static boolean isValidServoAxis(int index) {
            return index >= 0 && index < J33Config.J33_SERVO_AXES;
        }
    }

    public static final class J33WeiMath {
        public static BigInteger addSafe(BigInteger a, BigInteger b) {
            if (a == null) a = BigInteger.ZERO;
            if (b == null) b = BigInteger.ZERO;
            return a.add(b);
        }
        public static BigInteger subSafe(BigInteger a, BigInteger b) {
            if (a == null) a = BigInteger.ZERO;
            if (b == null) b = BigInteger.ZERO;
            BigInteger r = a.subtract(b);
            return r.signum() < 0 ? BigInteger.ZERO : r;
        }
    }

    public List<J33GripEngagedEvent> getGripEngagedEvents() {
        List<J33GripEngagedEvent> out = new ArrayList<>();
        for (Object o : eventLog) {
            if (o instanceof J33GripEngagedEvent) out.add((J33GripEngagedEvent) o);
        }
        return out;
    }

    public List<J33TargetAcquiredEvent> getTargetAcquiredEvents() {
        List<J33TargetAcquiredEvent> out = new ArrayList<>();
        for (Object o : eventLog) {
            if (o instanceof J33TargetAcquiredEvent) out.add((J33TargetAcquiredEvent) o);
        }
        return out;
    }

    public List<J33ClawCalibratedEvent> getClawCalibratedEvents() {
        List<J33ClawCalibratedEvent> out = new ArrayList<>();
        for (Object o : eventLog) {
            if (o instanceof J33ClawCalibratedEvent) out.add((J33ClawCalibratedEvent) o);
        }
        return out;
    }

    public List<J33IronClawActivatedEvent> getIronClawActivatedEvents() {
        List<J33IronClawActivatedEvent> out = new ArrayList<>();
        for (Object o : eventLog) {
            if (o instanceof J33IronClawActivatedEvent) out.add((J33IronClawActivatedEvent) o);
        }
        return out;
    }

    public static final class J33GasEstimates {
        public static final int OPEN_SESSION = 180_000;
        public static final int CLOSE_SESSION = 95_000;
        public static final int CALIBRATE = 220_000;
        public static final int ACQUIRE_TARGET = 150_000;
        public static final int ENGAGE_GRIP = 120_000;
        public static final int ACTIVATE_IRON_CLAW = 140_000;
        public static final int ATTACH_PAYLOAD = 160_000;
        public static final int PUSH_AI_DECISION = 100_000;
        public static final int SET_PAUSED = 55_000;
        public static final int RELEASE_GRIP = 85_000;
    }

    public static final class J33ChainIds {
        public static final long MAINNET = 1L;
        public static final long SEPOLIA = 11155111L;
        public static final long POLYGON = 137L;
        public static final long ARBITRUM_ONE = 42161L;
        public static final long OPTIMISM = 10L;
        public static final long BSC = 56L;
        public static final long AVALANCHE = 43114L;
        public static final long BASE = 8453L;
    }

    public static final class J33NetworkConfig {
        public static final String MAINNET_NAME = "mainnet";
        public static final String SEPOLIA_NAME = "sepolia";
        public static final String POLYGON_NAME = "polygon";
        public static final String ARBITRUM_NAME = "arbitrum-one";
        public static final String OPTIMISM_NAME = "optimism";
        public static final String BSC_NAME = "bsc";
        public static final String AVALANCHE_NAME = "avalanche";
        public static final String BASE_NAME = "base";
        public static long chainIdForName(String name) {
            if (name == null) return 0L;
            switch (name.toLowerCase()) {
