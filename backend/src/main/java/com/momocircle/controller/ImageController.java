package com.momocircle.controller;

import com.momocircle.security.CurrentUserService;
import com.momocircle.service.ImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * POST /api/images  (authenticated business owner, multipart field "file") -> { id, url }
 * GET  /api/images/{id}  (public, read-only) -> the image bytes
 */
@RestController
public class ImageController {

    private final ImageService imageService;
    private final CurrentUserService currentUser;

    public ImageController(ImageService imageService, CurrentUserService currentUser) {
        this.imageService = imageService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/api/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageService.Uploaded> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.upload(currentUser.require(), file));
    }

    @GetMapping("/api/images/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        ImageService.Stored s = imageService.read(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(s.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(s.data());
    }
}
