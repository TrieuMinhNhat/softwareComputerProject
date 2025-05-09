package com.example.linkshortener.service;

import com.example.linkshortener.data.dto.CreationRequest;
import com.example.linkshortener.data.entity.Data;
import com.example.linkshortener.data.repository.DataRepository;
import com.example.linkshortener.util.CustomUUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public final class DataService {
    private static final short MAX_SAVE_RETRIES = 5;
    @Autowired
    private final DataRepository dataRepository;

    @Autowired
    private final CacheService cacheService;

    @Value("${cache.enabled:false}") // false is default if not set
    private boolean cacheEnabled;


    public String findOrigin(String shortenedUrl) {
        // Find info from cache first
        if (cacheEnabled) {
            String cachedUrl = cacheService.getOriginalUrlFromCache(shortenedUrl);
            if (cachedUrl != null){
                return cachedUrl;
            }
        }

        Data data = dataRepository.findByShortenedUrl(shortenedUrl).orElse(null);
        if (data != null) {
            // Check if the URL has expired
            // URL is expired if expirationTime is not null and is before the current time
            // If expirationTime is null, the URL is not expired
            if (data.getExpirationTime() == null ||
                    data.getExpirationTime().isAfter(LocalDateTime.now())) {
                data.setClickCount(data.getClickCount() + 1);
                dataRepository.save(data);

                if (cacheEnabled) {
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime expTime = data.getExpirationTime();
                    cacheService.saveToCache(shortenedUrl, data.getUrl(), expTime != null ?
                            Duration.between(expTime, now).toSeconds() : null);
                }
                return data.getUrl();
            } else {
                deleteUrl(shortenedUrl);
                return null;
            }
        }
        
        return null;
    }

    private Data generateShortenedUrlAndSave(Data data) {
        Random random = new SecureRandom();
        for (int i = 0; i < MAX_SAVE_RETRIES; i++) {
            try {
                String shortenedUrl = CustomUUID.random(random);
                data.setShortenedUrl(shortenedUrl);
                return dataRepository.save(data);
            } catch (Exception ignored) {
            }
        }
        return null; // If all retries fail, return null
    }

    public String shortenUrl(CreationRequest request) throws SQLIntegrityConstraintViolationException {
        System.err.println(request);
        String url = request.getUrl();
        Long ttlMinute = request.getTtlMinute();
        String customShortenedUrl = request.getCustomShortenedUrl();

        LocalDateTime creationTime = LocalDateTime.now();
        LocalDateTime expirationTime = ttlMinute == null ?
                null : creationTime.plusMinutes(ttlMinute);

        Data data = Data.builder()
                .url(url)
                .creationTime(creationTime)
                .expirationTime(expirationTime)
                .clickCount(0)
                .build();

        if (customShortenedUrl != null) {
            System.err.println("Custom shortened URL: " + customShortenedUrl);
            // Case 1: Custom shortened URL is provided
            try {
                data.setShortenedUrl(customShortenedUrl.trim());
                dataRepository.save(data);
            } catch (Exception e) {
                throw new SQLIntegrityConstraintViolationException("Custom shortened URL already exists.");
            }
        } else {
            // Case 2: No custom shortened URL provided, generate a random one
            data = generateShortenedUrlAndSave(data);
            if (data == null) {
                throw new SQLIntegrityConstraintViolationException(
                        "Failed to generate a unique shortened URL, please try again or customize it."
                );
            }
        }

        String shortenedUrl = data.getShortenedUrl();
        if (cacheEnabled) {
            cacheService.saveToCache(shortenedUrl, url, ttlMinute != null ? ttlMinute * 60 : null);
        }
        return shortenedUrl;
    }

   
    public void deleteUrl(String shortenedUrl) {
        dataRepository.findByShortenedUrl(shortenedUrl)
                .ifPresent(dataRepository::delete); // Shorter lambda

        cacheService.deleteFromCache(shortenedUrl); // Still delete from Redis just in case
    }

    public void deleteAll() {
        dataRepository.deleteAll();
    }

    public List<Data> findAll(int page, int size) {
        return dataRepository.findAll(PageRequest.of(page, size)).getContent();
    }

}
