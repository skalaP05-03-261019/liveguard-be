package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;

/**
 * liveId는 방송 도입 전에 저장된 메시지면 null이다(A-17). moderationReasonCode는 작성자 본인에게 보내는 응답에서
 * HELD·HIDDEN일 때만 채우고, 그 외에는 null이다(PASS·복구된 PUBLIC 포함).
 */
public record Message(
		UUID messageId,
		UUID liveId,
		UUID channelId,
		String authorAlias,
		String text,
		Visibility visibility,
		int version,
		Instant sentAt,
		boolean isMine,
        ModerationReasonCode moderationReasonCode,
        String moderationNotice, Instant timeoutUntil,
        com.lifeguards.liveguard.contracts.enums.ReviewStatus reviewStatus) {
    public Message(UUID messageId,UUID liveId,UUID channelId,String authorAlias,String text,Visibility visibility,int version,Instant sentAt,boolean isMine){
        this(messageId,liveId,channelId,authorAlias,text,visibility,version,sentAt,isMine,null,null,null,null);
    }
    public Message(UUID messageId,UUID liveId,UUID channelId,String authorAlias,String text,Visibility visibility,int version,Instant sentAt,boolean isMine,ModerationReasonCode moderationReasonCode){
        this(messageId,liveId,channelId,authorAlias,text,visibility,version,sentAt,isMine,moderationReasonCode,null,null,null);
    }

	public Message {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(authorAlias, "authorAlias");
		DtoValidation.requireNonNull(text, "text");
		DtoValidation.requireNonNull(visibility, "visibility");
		DtoValidation.requireNonNull(sentAt, "sentAt");
	}
}
