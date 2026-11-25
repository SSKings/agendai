package com.sskings.agendai.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.conditions.ArchPredicates.are;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/** Fronteiras entre módulos (ADR-001), verificadas a cada build. */
class ArchitectureTest {

    static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.sskings.agendai");

    @Test
    @DisplayName("Controllers não falam com repositórios: tudo passa por um serviço (onde ficam as regras e o guard)")
    void controllersUseServices() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat(are(assignableTo(Repository.class)))
                .check(APP);
    }

    @Test
    @DisplayName("Entidades JPA nunca saem pela API: as rotas devolvem DTOs")
    void noEntitiesInApi() {
        noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should().haveRawReturnType(com.tngtech.archunit.base.DescribedPredicate.describe("an @Entity",
                        c -> c.isAnnotatedWith(Entity.class)))
                .check(APP);
    }

    @Test
    @DisplayName("Repositórios do estabelecimento só são usados dentro do módulo tenant (o guard de acesso fica lá)")
    void tenantRepositoriesStayInModule() {
        classes().that().areAssignableTo(Repository.class).and().resideInAPackage("..tenant..")
                .should().onlyBeAccessed().byClassesThat().resideInAPackage("..tenant..")
                .check(APP);
    }

    @Test
    @DisplayName("Repositórios e entidades não são públicos (o compilador barra o uso fora do módulo); exceções antigas: user e tenant")
    void moduleInternalsArePackagePrivate() {
        classes().that().resideOutsideOfPackages("..user..", "..tenant..")
                .and(assignableTo(Repository.class).or(com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates
                        .annotatedWith(jakarta.persistence.Entity.class)))
                .should().notBePublic()
                .check(APP);
    }

    @Test
    @DisplayName("Agenda, catálogo e estabelecimento não dependem dos agendamentos: booking usa os outros, nunca o contrário (ADR-008)")
    void scheduleDoesNotDependOnBooking() {
        noClasses().that().resideInAnyPackage("..schedule..", "..catalog..", "..tenant..")
                .should().dependOnClassesThat().resideInAPackage("..booking..")
                .check(APP);
    }

    @Test
    @DisplayName("Repositório de usuários só é usado pelos módulos de usuário, autenticação e segurança")
    void userRepositoryUsage() {
        classes().that().areAssignableTo(Repository.class).and().resideInAPackage("..user..")
                .should().onlyBeAccessed().byClassesThat().resideInAnyPackage("..user..", "..auth..", "..security..", "..config..")
                .check(APP);
    }
}
