package com.jubitus.travellers.entity.entityAI.follow.formations;

public enum WorkerFormationStyle {
    COMPACT,
    LINE,
    COLUMN,
    WEDGE,
    ADAPTIVE;

    public WorkerFormationStyle next() {
        WorkerFormationStyle[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}