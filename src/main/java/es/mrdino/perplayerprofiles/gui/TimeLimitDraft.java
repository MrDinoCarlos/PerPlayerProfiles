package es.mrdino.perplayerprofiles.gui;

public class TimeLimitDraft {
    private final String profileName;
    private long maxMinutes;
    private long renewHours;

    public TimeLimitDraft(String profileName, long maxMinutes, long renewHours) {
        this.profileName = profileName;
        this.maxMinutes = Math.max(0L, maxMinutes);
        this.renewHours = Math.max(0L, renewHours);
    }

    public String getProfileName() {
        return profileName;
    }

    public long getMaxMinutes() {
        return maxMinutes;
    }

    public long getRenewHours() {
        return renewHours;
    }

    public void addMaxMinutes(long amount) {
        maxMinutes = Math.max(0L, maxMinutes + amount);
    }

    public void addRenewHours(long amount) {
        renewHours = Math.max(0L, renewHours + amount);
    }
}
