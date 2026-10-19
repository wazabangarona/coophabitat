package sn.coophabitat.admin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DataRetentionPolicyRepository extends JpaRepository<DataRetentionPolicy, String> {
}
