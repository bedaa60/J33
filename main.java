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
