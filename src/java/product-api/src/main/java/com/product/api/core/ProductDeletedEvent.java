package com.product.api.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDeletedEvent {
    private String productId;

    public ProductDeletedEvent(){
        this.productId = "";
    }

    public ProductDeletedEvent(String productId) {
        this.productId = productId;
    }

    public String getProductId() {
        return productId;
    }
}
