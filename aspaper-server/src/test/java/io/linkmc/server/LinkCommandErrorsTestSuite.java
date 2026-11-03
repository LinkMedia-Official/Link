package io.linkmc.server;

import io.linkmc.core.ConflictException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkCommandErrorsTestSuite {
    @Test void nestedExistingWorldErrorSuggestsEntry(){
        var error=new CompletionException(new ExecutionException(ConflictException.worldExists("spawn",false)));
        String message=LinkCommand.friendlyError(error);
        assertTrue(message.contains("/템플릿 입장 spawn"));assertTrue(message.contains("다른 이름"));
    }
    @Test void deletedWorldDoesNotSuggestImpossibleEntry(){
        String message=LinkCommand.friendlyError(ConflictException.worldExists("spawn",true));
        assertTrue(message.contains("재사용할 수 없습니다"));assertFalse(message.contains("/템플릿 입장"));
    }
    @Test void unclassifiedFailuresRemainLoggedWithoutExposingDetails(){
        assertNull(LinkCommand.friendlyError(new ConflictException("secret connection detail")));
        assertNull(LinkCommand.friendlyError(new CompletionException(new java.sql.SQLException("secret password"))));
    }
    @Test void editorGuidanceSurvivesAsyncWrapping(){
        assertEquals("편집 중입니다",LinkCommand.friendlyError(new CompletionException(new LinkEditors.EditorError("편집 중입니다"))));
    }
}
