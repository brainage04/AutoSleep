package io.github.brainage04.autosleep;

/**
 * Which ticks a tick counter counts; {@link AutoSleeper} names each counter's clock where it is
 * declared. No clock counts a tick in which a singleplayer game is paused: the integrated server
 * then stands still (IntegratedServer.java:111-121 in 1.21.11), and so do the world, its monsters
 * and the time the server sends.
 */
enum Clock {
    /** Every tick in which the world runs; an open screen does not stop it. */
    WORLD,

    /**
     * The ticks in which the player could right-click: the world runs, no screen is open and the
     * player is not in spectator mode.
     */
    PLAYER;

    /**
     * Whether this clock counts a tick in which the singleplayer game is {@code paused} or not, and
     * in which a player who is not a spectator is there with no screen open ({@code
     * playerCouldClick}) or not.
     */
    boolean counts(boolean paused, boolean playerCouldClick) {
        if (paused) {
            return false;
        }
        return switch (this) {
            case WORLD -> true;
            case PLAYER -> playerCouldClick;
        };
    }
}
