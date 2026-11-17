package com.caseware.fanout.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class HexagonalArchitectureTest {
    private static final String BASE = "com.caseware.fanout.";
    private static final JavaClasses CODE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.caseware.fanout");

    @Test void domainDependsOnlyOnItselfAndJava() {
        classes().that().resideInAPackage(BASE + "domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", BASE + "domain..")
                .check(CODE);
    }

    @Test void applicationDependsOnlyOnDomainPortsAndJava() {
        classes().that().resideInAPackage(BASE + "application..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", BASE + "domain..", BASE + "application..").check(CODE);
        noClasses().that().resideInAnyPackage(BASE + "domain..", BASE + "application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .check(CODE);
    }

    @Test void portsDoNotDependOnServicesOrOnTheOppositeDirection() {
        for (String direction : new String[]{"in", "out"}) {
            classes().that().resideInAPackage(BASE + "application.port." + direction + "..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", BASE + "domain..",
                            BASE + "application.port." + direction + "..").check(CODE);
        }
    }

    @Test void drivingAdaptersUseOnlyInputPorts() {
        noClasses().that().resideInAPackage(BASE + "adapter.in..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + "application.service..",
                        BASE + "application.port.out..", BASE + "adapter.out..", BASE + "bootstrap..")
                .check(CODE);
    }

    @Test void drivenAdaptersCannotInvokeUseCasesOrBootstrap() {
        noClasses().that().resideInAPackage(BASE + "adapter.out..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + "application.service..",
                        BASE + "application.port.in..", BASE + "adapter.in..", BASE + "bootstrap..")
                .check(CODE);
    }
}
