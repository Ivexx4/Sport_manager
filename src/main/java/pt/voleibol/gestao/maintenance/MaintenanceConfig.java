package pt.voleibol.gestao.maintenance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DataRetentionProperties.class)
class MaintenanceConfig {
}
