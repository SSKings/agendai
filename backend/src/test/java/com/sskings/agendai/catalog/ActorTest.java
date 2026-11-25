package com.sskings.agendai.catalog;

import com.sskings.agendai.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {

    @Test
    @DisplayName("Dono age por todos; profissional só pelo vinculado; sem vínculo, por ninguém")
    void rules() {
        UUID joao = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        CatalogService.Actor owner = new CatalogService.Actor(true, null);
        CatalogService.Actor pro = new CatalogService.Actor(false, joao);
        CatalogService.Actor loose = new CatalogService.Actor(false, null);
        assertThat(owner.actsFor(beto)).isTrue();
        assertThat(pro.actsFor(joao)).isTrue();
        assertThat(pro.actsFor(beto)).isFalse();
        assertThat(loose.actsFor(joao)).isFalse();
        pro.requireFor(joao);
        owner.requireFor(beto);
        assertThatThrownBy(() -> pro.requireFor(beto)).isInstanceOf(ApiException.class).hasMessage(CatalogService.OWN_AGENDA);
        assertThat(pro.own()).isEqualTo(joao);
        assertThatThrownBy(loose::own).isInstanceOf(ApiException.class).hasMessage(CatalogService.OWN_AGENDA);
    }
}
