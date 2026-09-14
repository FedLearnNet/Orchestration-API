package bio.cosy.featurecloud.orchestration.base;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Setter
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Date updatedAt;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Version
    private Long version = 0L;

    public BaseEntity() {
    }

    public String toString() {
        String var10000 = this.getClass().getSimpleName();
        return var10000 + "<" + this.id + ">";
    }

}