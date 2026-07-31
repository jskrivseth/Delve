/*
 * Title and world selection screens.
 */
package cydi;

import java.util.ArrayList;
import java.util.List;

/**
 * The screens shown before a world is running: the title menu and the world
 * picker. Both reuse {@link MenuPanel} for layout and hit testing.
 */
public class TitleScreen {

    private final MenuPanel panel = new MenuPanel();
    private final List<String> labels = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int hovered = -1;
    private boolean showingWorlds;
    private List<String> worlds = new ArrayList<>();

    public void openWorldList() {
        showingWorlds = true;
        worlds = SaveGame.list();
        hovered = -1;
    }

    public void openMain() {
        showingWorlds = false;
        hovered = -1;
    }

    public boolean isShowingWorlds() {
        return showingWorlds;
    }

    private void buildRows() {
        labels.clear();
        values.clear();
        if (showingWorlds) {
            if (worlds.isEmpty()) {
                labels.add("No saved worlds");
                values.add("");
            } else {
                for (String world : worlds) {
                    labels.add(world);
                    values.add("Load");
                }
            }
            labels.add("Back");
            values.add("");
        } else {
            labels.add("New World");
            values.add("");
            labels.add("Load World");
            values.add(SaveGame.list().size() + " saved");
            labels.add("Quit");
            values.add("Esc");
        }
    }

    public void updateHover(double mouseX, double mouseY) {
        hovered = panel.rowAt(mouseX, mouseY);
    }

    public void click(double mouseX, double mouseY) {
        int index = panel.rowAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        if (showingWorlds) {
            if (worlds.isEmpty()) {
                // Only the Back row exists.
                openMain();
                return;
            }
            if (index < worlds.size()) {
                Game.INSTANCE.startWorld(SaveGame.load(worlds.get(index)));
            } else {
                openMain();
            }
            return;
        }
        switch (index) {
            case 0 -> Game.INSTANCE.startWorld(
                    SaveGame.create(SaveGame.nextDefaultName(), new java.util.Random().nextLong()));
            case 1 -> openWorldList();
            case 2 -> Game.WINDOW.requestClose();
            default -> {
            }
        }
    }

    public void render() {
        Window window = Game.WINDOW;
        if (window == null) {
            return;
        }
        float w = window.getWidth();
        float h = window.getHeight();

        buildRows();
        panel.layout(w, h, labels.size());
        panel.render(w, h, showingWorlds ? "Select World" : "Delve", labels, values, hovered);
    }
}
