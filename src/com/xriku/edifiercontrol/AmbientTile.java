package com.xriku.edifiercontrol;

public final class AmbientTile extends ControlTile {
    @Override
    protected String command() {
        return Protocol.AMBIENT;
    }

    @Override
    protected int iconId() {
        return R.drawable.ic_ambient;
    }

    @Override
    protected String tileLabel() {
        return "WH950NB Ambient";
    }
}
