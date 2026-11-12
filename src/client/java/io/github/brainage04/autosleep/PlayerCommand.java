package io.github.brainage04.autosleep;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import java.util.List;
import java.util.stream.Stream;

/** A Baritone command that runs with the player's own settings; see {@link AutoSleeper}. */
final class PlayerCommand implements ICommand {
    private final ICommand command;
    private final AutoSleeper autoSleeper;

    PlayerCommand(ICommand command, AutoSleeper autoSleeper) {
        this.command = command;
        this.autoSleeper = autoSleeper;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        autoSleeper.beforePlayerCommand();
        try {
            command.execute(label, args);
        } finally {
            autoSleeper.afterPlayerCommand();
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return command.tabComplete(label, args);
    }

    @Override
    public String getShortDesc() {
        return command.getShortDesc();
    }

    @Override
    public List<String> getLongDesc() {
        return command.getLongDesc();
    }

    @Override
    public List<String> getNames() {
        return command.getNames();
    }

    @Override
    public boolean hiddenFromHelp() {
        return command.hiddenFromHelp();
    }
}
