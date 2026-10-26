package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.UploadedImage;
import com.momocircle.domain.User;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.UploadedImageRepository;
import com.momocircle.services.UploadedImageRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Seller image uploads. Only the signed-in owner of a business may upload (the image is recorded against
 * their business); anyone may read an image by its unguessable id because images are customer-facing.
 */
@Service
public class ImageService {

    public record Uploaded(String id, String url, String contentType, int sizeBytes) {}
    public record Stored(String contentType, byte[] data) {}

    private final UploadedImageRepository images;
    private final BusinessService businessService;

    public ImageService(UploadedImageRepository images, BusinessService businessService) {
        this.images = images;
        this.businessService = businessService;
    }

    @Transactional
    public Uploaded upload(User owner, MultipartFile file) {
        Business business = businessService.requireOwn(owner);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose an image file");
        if (file.getSize() > UploadedImageRules.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Image is too large (maximum 2 MB)");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read that file");
        }
        if (bytes.length > UploadedImageRules.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Image is too large (maximum 2 MB)");
        }
        String type = UploadedImageRules.detectType(bytes);
        if (type == null) throw ApiException.badRequest("Only JPEG, PNG or WebP images are allowed");
        if (images.countByBusinessId(business.getId()) >= UploadedImageRules.MAX_IMAGES_PER_BUSINESS) {
            throw ApiException.conflict("Image limit reached for this business");
        }
        UploadedImage img = new UploadedImage();
        img.setBusinessId(business.getId());
        img.setUploadedBy(owner.getId());
        img.setContentType(type);
        img.setSizeBytes(bytes.length);
        img.setData(bytes);
        images.save(img);
        return new Uploaded(img.getId(), UploadedImageRules.PATH_PREFIX + img.getId(), type, bytes.length);
    }

    @Transactional(readOnly = true)
    public Stored read(String id) {
        UploadedImage img = images.findById(id).orElseThrow(() -> ApiException.notFound("Image not found"));
        return new Stored(img.getContentType(), img.getData());
    }
}
