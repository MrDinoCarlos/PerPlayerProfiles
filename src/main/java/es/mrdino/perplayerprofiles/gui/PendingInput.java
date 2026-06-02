package es.mrdino.perplayerprofiles.gui;

public class PendingInput {
    private final PendingInputType type;
    private final String profileName;
    private final String value;

    public PendingInput(PendingInputType type, String profileName) {
        this(type, profileName, "");
    }

    public PendingInput(PendingInputType type, String profileName, String value) {
        this.type = type;
        this.profileName = profileName;
        this.value = value == null ? "" : value;
    }

    public PendingInputType getType() {
        return type;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getValue() {
        return value;
    }

    public PendingInput withValue(String newValue) {
        return new PendingInput(type, profileName, newValue);
    }
}
