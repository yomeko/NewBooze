package com.example.demo.repository;

import com.example.demo.entity.Brewery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BreweryRepository extends JpaRepository<Brewery, Long> {
    Optional<Brewery> findFirstByName(String name);
}
