package com.odel.content.service;

import com.odel.content.entity.Item;

/** A copy of an item's content (copying a course, swapping in a replacement): the same file or video, no new upload. */
final class ItemCopier {
    private ItemCopier() {}

    static Item copy(Item i) {
        Item c = new Item();
        c.setPosition(i.getPosition());
        c.setKind(i.getKind());
        c.setTitle(i.getTitle());
        c.setHidden(i.isHidden());
        copyContent(i, c);
        c.setUrl(i.getUrl());
        c.setNote(i.getNote());
        c.setWarnData(i.isWarnData());
        c.setBody(i.getBody());
        c.setCreatedByName(i.getCreatedByName());
        return c;
    }

    /** The uploaded content (file or video) of one item onto another. */
    static void copyContent(Item from, Item to) {
        to.setStatus(from.getStatus());
        to.setStorageKey(from.getStorageKey());
        to.setOriginalKey(from.getOriginalKey());
        to.setFileName(from.getFileName());
        to.setContentType(from.getContentType());
        to.setSizeBytes(from.getSizeBytes());
        to.setOriginalSizeBytes(from.getOriginalSizeBytes());
        to.setPages(from.getPages());
        to.setPagesChecked(from.isPagesChecked());
        to.setConverted(from.isConverted());
        to.setVideoId(from.getVideoId());
        to.setDurationSeconds(from.getDurationSeconds());
        to.setQualities(from.getQualities());
        to.setEncodeProgress(from.getEncodeProgress());
        to.setAudioStatus(from.getAudioStatus());
        to.setAudioKey(from.getAudioKey());
        to.setAudioSizeBytes(from.getAudioSizeBytes());
        to.setFailureCode(from.getFailureCode());
        to.setFailureMessage(from.getFailureMessage());
        to.setUploadedAt(from.getUploadedAt());
        to.setReadyAt(from.getReadyAt());
    }
}
