package ai.careglove.user;

import ai.careglove.common.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    Optional<UserEntity> findByEmailIgnoreCase(String email);
    List<UserEntity> findByRole(Role role);
    List<UserEntity> findByRoleAndActiveTrueOrderByNameAsc(Role role);
    List<UserEntity> findByHospitalAndRoleAndActiveTrueOrderByNameAsc(String hospital, Role role);
    List<UserEntity> findByHospitalAndActiveTrueOrderByNameAsc(String hospital);
    Optional<UserEntity> findByEmployeeIdAndHospital(String employeeId, String hospital);
    boolean existsByEmailIgnoreCase(String email);
}
