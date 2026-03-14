package com.taxonomy.model;

import jakarta.persistence.*;

/**
 * A security role (e.g. ROLE_USER, ROLE_ARCHITECT, ROLE_ADMIN).
 */
@Entity
@Table(name = "app_role")
public class AppRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public AppRole() {
    }

    public AppRole(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
