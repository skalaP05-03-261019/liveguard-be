package com.lifeguards.liveguard.platform;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
@RestController
class LiveChatEventController {
 private final LiveService lives;
 LiveChatEventController(LiveService lives){this.lives=lives;}
 @GetMapping("/api/lives/{liveId}/events")
 Object events(@PathVariable UUID liveId,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest request){
  return lives.subscribe(liveId,user,request.getSession().getId(),false);
 }
}
