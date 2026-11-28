package com.odel.content.client;

/** The video host refused or could not be reached. */
public class VideoHostException extends RuntimeException {
    public VideoHostException(String message) {
        super(message);
    }
}
