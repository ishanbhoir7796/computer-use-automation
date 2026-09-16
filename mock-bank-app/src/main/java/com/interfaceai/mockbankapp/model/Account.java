package com.interfaceai.mockbankapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    private String type;      // "Checking", "Savings", "Trust"
    private String number;    // e.g. "CHK-88213"
    private double balance;
}