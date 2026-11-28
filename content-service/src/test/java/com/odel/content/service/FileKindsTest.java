package com.odel.content.service;

import com.odel.common.ApiException;
import com.odel.content.entity.Item;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileKindsTest {
    @Test
    void kindsFromTheFileName() {
        assertThat(FileKinds.kindOf("lecture-06.MP4")).isEqualTo(Item.Kind.VIDEO);
        assertThat(FileKinds.kindOf("week5.pptx")).isEqualTo(Item.Kind.SLIDES);
        assertThat(FileKinds.kindOf("notes.docx")).isEqualTo(Item.Kind.NOTES);
        assertThat(FileKinds.kindOf("notes.pdf")).isEqualTo(Item.Kind.PDF);
        assertThat(FileKinds.kindOf("talk.m4a")).isEqualTo(Item.Kind.AUDIO);
    }

    @Test
    void refusalsSayWhatToDo() {
        assertThatThrownBy(() -> FileKinds.kindOf("work.zip")).isInstanceOf(ApiException.class)
                .hasMessage("We cannot use .zip files. Upload a video, audio, PDF, PowerPoint or Word file.");
        assertThatThrownBy(() -> FileKinds.kindOf("deck.key")).hasMessage("Export Keynote files to PowerPoint or PDF first.");
        assertThatThrownBy(() -> FileKinds.checkSize(Item.Kind.VIDEO, FileKinds.VIDEO_MAX + 1))
                .hasMessage("Videos can be up to 2 GB. Shorten the video or export it at a lower quality, then try again.");
        FileKinds.checkSize(Item.Kind.VIDEO, FileKinds.VIDEO_MAX);
        assertThatThrownBy(() -> FileKinds.checkSize(Item.Kind.PDF, 0)).hasMessage("That file is empty. Choose the file again.");
    }
}
