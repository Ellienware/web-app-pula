package com.momocircle.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * PULA Food: optional structured attributes for a Product that is a prepared meal. Every
 * field is optional so a small kitchen can list food with just a name and a price. Values
 * are normalised upper-case tokens (CHICKEN, PAP) rather than enums, so the model stays
 * generic across kasi, home-kitchen and chain menus.
 */
@Entity
@Table(name = "product_food_details")
public class ProductFoodDetails extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    private String foodType;
    private String protein;
    private String starch;
    private String portionSize;
    private String servingSize;
    private String spiceLevel;
    private String portionDescription;
    private Integer prepTimeMinutes;

    /** Optional capacity (portions available today). Null means not capacity-limited. */
    private Integer dailyQuantity;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_food_sides", joinColumns = @JoinColumn(name = "food_details_id"))
    @Column(name = "side", length = 64)
    private Set<String> sides = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_dietary_tags", joinColumns = @JoinColumn(name = "food_details_id"))
    @Column(name = "tag", length = 64)
    private Set<String> dietaryTags = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_allergens", joinColumns = @JoinColumn(name = "food_details_id"))
    @Column(name = "allergen", length = 64)
    private Set<String> allergens = new LinkedHashSet<>();

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public String getFoodType() { return foodType; }
    public void setFoodType(String foodType) { this.foodType = foodType; }
    public String getProtein() { return protein; }
    public void setProtein(String protein) { this.protein = protein; }
    public String getStarch() { return starch; }
    public void setStarch(String starch) { this.starch = starch; }
    public String getPortionSize() { return portionSize; }
    public void setPortionSize(String portionSize) { this.portionSize = portionSize; }
    public String getServingSize() { return servingSize; }
    public void setServingSize(String servingSize) { this.servingSize = servingSize; }
    public String getSpiceLevel() { return spiceLevel; }
    public void setSpiceLevel(String spiceLevel) { this.spiceLevel = spiceLevel; }
    public String getPortionDescription() { return portionDescription; }
    public void setPortionDescription(String portionDescription) { this.portionDescription = portionDescription; }
    public Integer getPrepTimeMinutes() { return prepTimeMinutes; }
    public void setPrepTimeMinutes(Integer prepTimeMinutes) { this.prepTimeMinutes = prepTimeMinutes; }
    public Integer getDailyQuantity() { return dailyQuantity; }
    public void setDailyQuantity(Integer dailyQuantity) { this.dailyQuantity = dailyQuantity; }
    public Set<String> getSides() { return sides; }
    public void setSides(Set<String> sides) { this.sides = sides; }
    public Set<String> getDietaryTags() { return dietaryTags; }
    public void setDietaryTags(Set<String> dietaryTags) { this.dietaryTags = dietaryTags; }
    public Set<String> getAllergens() { return allergens; }
    public void setAllergens(Set<String> allergens) { this.allergens = allergens; }
}
