package com.sskings.agendai.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerPrivacyTest {

    static Customer customer(String name, String email) {
        Customer c = mock(Customer.class);
        when(c.getName()).thenReturn(name);
        when(c.getEmail()).thenReturn(email);
        return c;
    }

    static Booking contact(String name, String email) {
        Booking b = mock(Booking.class);
        when(b.getContactName()).thenReturn(name);
        when(b.getContactEmail()).thenReturn(email);
        return b;
    }

    @Test
    @DisplayName("Contato do titular: mesmo nome, ou mesmo e-mail do cadastro; sem contato (painel) ou cadastro sem e-mail e outro nome: não")
    void titular() {
        assertThat(CustomerPrivacy.titular(customer("Bruno Lima", "b@x.com"), contact("BRUNO LIMA", "outro@x.com"))).isTrue();
        assertThat(CustomerPrivacy.titular(customer("Bruno Lima", "b@x.com"), contact("B. Lima", "B@X.com"))).isTrue();
        assertThat(CustomerPrivacy.titular(customer("Bruno Lima", "b@x.com"), contact("Carla Dias", "c@x.com"))).isFalse();
        assertThat(CustomerPrivacy.titular(customer("Bruno Lima", null), contact("Carla Dias", "c@x.com"))).isFalse();
        assertThat(CustomerPrivacy.titular(customer("Bruno Lima", "b@x.com"), contact(null, null))).isFalse();
    }

    @SuppressWarnings("unchecked")
    static TransactionTemplate direct() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Integer>) i.getArgument(0)).doInTransaction(null));
        return tx;
    }

    @Test
    @DisplayName("Retenção: lote cheio repete até o teto de lotes; nada a fazer, uma ida só")
    void retentionBatches() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T09:00:00Z"), ZoneOffset.UTC);
        CustomerRepository full = mock(CustomerRepository.class);
        when(full.anonymizeStale(any(), anyInt())).thenReturn(2);
        assertThat(new CustomerRetention(full, clock, direct(), 2, 3).run()).isEqualTo(6);
        verify(full, times(3)).anonymizeStale(any(), anyInt());

        CustomerRepository empty = mock(CustomerRepository.class);
        assertThat(new CustomerRetention(empty, clock, direct(), 2, 3).run()).isZero();
        verify(empty, times(1)).anonymizeStale(any(), anyInt());
    }
}
