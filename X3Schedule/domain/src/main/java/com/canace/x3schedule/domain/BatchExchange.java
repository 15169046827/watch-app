package com.canace.x3schedule.domain;

/** Phone drives rounds; after its commit it polls until the watch also commits. */
public final class BatchExchange {
    private boolean localDone;
    private boolean remoteDone;

    public void localAck(boolean committed) {
        localDone = committed;
    }

    public void remoteAck(boolean committed) {
        remoteDone = committed;
    }

    public int nextKind(boolean localChangesPending) {
        if (localDone && remoteDone && localChangesPending) {
            localDone = false;
            remoteDone = false;
        }
        if (!localDone) {
            return 3;
        }
        return remoteDone ? 0 : 5;
    }
}
