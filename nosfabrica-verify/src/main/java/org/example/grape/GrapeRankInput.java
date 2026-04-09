package org.example.grape;

public class GrapeRankInput {
    private String rater;
    private String ratee;
    private String context = "not a bot";
    private double rating;
    private double confidence;

    public GrapeRankInput(String rater, String ratee, double rating, double confidence) {
        this.rater = rater;
        this.ratee = ratee;
        this.rating = rating;
        this.confidence = confidence;
    }

    public String getRater() { return rater; }
    public String getRatee() { return ratee; }
    public String getContext() { return context; }
    public double getRating() { return rating; }
    public double getConfidence() { return confidence; }
}
