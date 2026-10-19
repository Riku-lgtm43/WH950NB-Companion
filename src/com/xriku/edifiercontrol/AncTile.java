package com.xriku.edifiercontrol;

public final class AncTile extends ControlTile {
    @Override
    protected String command() {
        return Protocol.ANC;
    }

    @Override
    protected int iconId() {
        return R.drawable.ic_anc;
    }

    @Override
    protected String tileLabel() {
        return "WH950NB ANC";
    }
}
