package com.rickh.ddblat.worker;

/** Supplies the next key index to operate on. Returns -1 when the phase's work is done. */
@FunctionalInterface
public interface IndexSource {
    int next();
}
