package net.jordimp.redistoolkit.jobqueue.port;

public interface Metrics {

    void submitted(String queue);

    void delivered(String queue);

    void acked(String queue);

    void failed(String queue);

    void reclaimed(String queue);

    void promoted(String queue);
}
