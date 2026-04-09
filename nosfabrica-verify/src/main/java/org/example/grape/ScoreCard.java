package org.example.grape;

public class ScoreCard {
    private String observer;
    private String observee;
    private String context = "not a bot";
    private double hops;
    private double averageScore = 0;
    private double input = 0;
    private double confidence = 0;
    private double influence = 0;
    private Boolean verified = null;

    public ScoreCard(String observer, String observee, double hops) {
        this.observer = observer;
        this.observee = observee;
        this.hops = hops;
    }

    public ScoreCard(String observer, String observee, double averageScore, double input, double confidence,
            double influence) {
        this.observer = observer;
        this.observee = observee;
        this.averageScore = averageScore;
        this.input = input;
        this.confidence = confidence;
        this.influence = influence;
        this.hops = 0;
    }

    public String getObserver() { return observer; }
    public String getObservee() { return observee; }
    public double getAverageScore() { return averageScore; }
    public void setAverageScore(double averageScore) { this.averageScore = averageScore; }
    public double getInput() { return input; }
    public void setInput(double input) { this.input = input; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public double getInfluence() { return influence; }
    public void setInfluence(double influence) { this.influence = influence; }
    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }
}
