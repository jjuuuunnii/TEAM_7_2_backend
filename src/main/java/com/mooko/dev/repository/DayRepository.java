package com.mooko.dev.repository;

import com.mooko.dev.domain.Day;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository

public interface DayRepository extends JpaRepository<Day, Long> {
}
