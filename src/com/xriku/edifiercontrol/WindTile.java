package com.xriku.edifiercontrol;

public final class WindTile extends ControlTile {
    @Override
    protected String command() {
        return Protocol.WIND;
    }

    @Override
    protected int iconId() {
        return R.drawable.ic_wind;
    }

    @Override
    protected String tileLabel() {
        return "WH950NB Wind";
    }
}
