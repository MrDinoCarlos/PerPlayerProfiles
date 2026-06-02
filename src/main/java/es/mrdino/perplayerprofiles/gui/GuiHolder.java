package es.mrdino.perplayerprofiles.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class GuiHolder implements InventoryHolder {
    private final GuiType type;
    private final String profileName;

    public GuiHolder(GuiType type, String profileName) {
        this.type = type;
        this.profileName = profileName;
    }

    public GuiType getType() {
        return type;
    }

    public String getProfileName() {
        return profileName;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
