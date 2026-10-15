package com.jubitus.travellers.debug.autotest.scenario;

/** What a scenario answers on each tick: still going, passed, not applicable here, or failed with a reason. */
public final class Outcome {

    public enum Kind {PENDING, PASS, SKIP, FAIL}

    public static final Outcome PENDING = new Outcome(Kind.PENDING, "");
    public static final Outcome PASS = new Outcome(Kind.PASS, "");

    public final Kind kind;
    public final String reason;

    private Outcome(Kind kind, String reason) {
        this.kind = kind;
        this.reason = reason;
    }

    public static Outcome skip(String reason) {
        return new Outcome(Kind.SKIP, reason);
    }

    public static Outcome fail(String reason) {
        return new Outcome(Kind.FAIL, reason);
    }

    public boolean isFinal() {
        return kind != Kind.PENDING;
    }
}
