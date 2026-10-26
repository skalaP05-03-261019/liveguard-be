package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import tools.jackson.databind.json.JsonMapper;

@Service
class MessageService {
	private final MessageRepository messages;
	private final JdbcChannelMembershipQuery memberships;
	private final LiveService moderation;
	private final JsonMapper mapper;

	MessageService(MessageRepository messages, JdbcChannelMembershipQuery memberships,
			LiveService moderation, JsonMapper mapper) {
		this.messages=messages;
		this.memberships=memberships;
		this.moderation=moderation;
		this.mapper=mapper;
	}

	@Transactional
	Message sendToLive(UUID liveId, AuthenticatedUser author, SendMessageRequest request) {
		requireStorable(request.text());
		return mapper.convertValue(moderation.send(liveId,author,request),Message.class);
	}

	@Transactional
	Message sendToChannel(UUID channelId, AuthenticatedUser author, SendMessageRequest request) {
		requireStorable(request.text());
		return mapper.convertValue(moderation.sendToChannel(channelId,author,request),Message.class);
	}

	@Transactional(readOnly=true)
	MessagePage historyOfLive(UUID liveId, AuthenticatedUser viewer, String cursor, int limit) {
		return mapper.convertValue(moderation.history(liveId,viewer,cursor,limit),MessagePage.class);
	}

	/** The deprecated channel history still includes pre-live messages without inventing broadcasts. */
	@Transactional(readOnly=true)
	MessagePage historyOfChannel(UUID channelId, AuthenticatedUser viewer, String cursor, int limit) {
		if(moderation.isAdmin(viewer.userId()))
			throw new ApiException(ErrorCode.FORBIDDEN,"Administrators must use liveId history");
		if(limit<1||limit>100)throw new InvalidContractException("limit must be between 1 and 100");
		MessageCursor after=cursor==null?null:MessageCursor.decode(cursor);
		AuthorRole role=memberships.memberRole(channelId,viewer.userId())
			.orElseThrow(()->new ApiException(ErrorCode.FORBIDDEN,"Not a member of this channel"));
		// 비회원에게 강퇴 여부가 드러나지 않도록 회원 확인 뒤에 한다
		moderation.checkChannelEntry(channelId,viewer.userId());
		var rows=messages.history(channelId,null,viewer.userId(),role==AuthorRole.MODERATOR,after,limit+1);
		var page=rows.subList(0,Math.min(limit,rows.size()));
		List<Message> items=page.stream().map(row->{
			boolean mine=row.authorId().equals(viewer.userId());
			return new Message(row.id(),row.liveId(),channelId,row.authorNickname(),row.text(),row.visibility(),
				row.version(),row.sentAt(),mine,mine&&row.visibility()!=Visibility.PUBLIC?row.moderationReasonCode():null);
		}).toList();
		return new MessagePage(items,rows.size()>limit?new MessageCursor(page.getLast().sentAt(),page.getLast().id()).encode():null);
	}

	private static void requireStorable(String text) {
		for(int i=0;i<text.length();i++) {
			char c=text.charAt(i);
			boolean lowNext=i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1));
			if(c=='\u0000'||Character.isLowSurrogate(c)||(Character.isHighSurrogate(c)&&!lowNext))
				throw new InvalidContractException("text contains unsupported characters");
			if(Character.isHighSurrogate(c))i++;
		}
	}
}
