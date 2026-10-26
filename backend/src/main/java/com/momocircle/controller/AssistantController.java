package com.momocircle.controller;

import com.momocircle.dto.AssistantDtos.AssistantRequest;
import com.momocircle.dto.AssistantDtos.AssistantResponse;
import com.momocircle.dto.AssistantDtos.ExecuteActionRequest;
import com.momocircle.dto.AssistantDtos.ExecuteActionResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.AssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant only INTERPRETS requests into a proposed action, which is stored
 * server-side (see AssistantService/AssistantProposal). Execution happens through
 * the normal, authorized circle/market endpoints after the user confirms by proposal
 * id — the client can never resend a different intent, amount, or circle than what
 * was actually proposed - so the AI can never move money on its own.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    private final CurrentUserService currentUser;

    public AssistantController(AssistantService assistantService, CurrentUserService currentUser) {
        this.assistantService = assistantService;
        this.currentUser = currentUser;
    }

    @PostMapping("/interpret")
    public AssistantResponse interpret(@Valid @RequestBody AssistantRequest req) {
        return assistantService.interpret(currentUser.require(), req);
    }

    @PostMapping("/execute")
    public ExecuteActionResponse execute(@Valid @RequestBody ExecuteActionRequest req) {
        return assistantService.execute(currentUser.require(), req);
    }
}
